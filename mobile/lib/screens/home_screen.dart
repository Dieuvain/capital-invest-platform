import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'kyc_screen.dart';
import 'money_screens.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.app, required this.photos});

  final AppState app;
  final PhotoSource photos;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with ApiAction {
  int? balance;
  Limits? limits;
  List<Entry> entries = const [];

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final api = widget.app.api;
    await run(widget.app, () async {
      final results = await Future.wait([api.balance(), api.limits(), api.entries(limit: 20)]);
      if (!mounted) return;
      setState(() {
        balance = results[0] as int;
        limits = results[1] as Limits;
        entries = results[2] as List<Entry>;
      });
    });
  }

  Future<void> _open(Widget screen) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => screen));
    if (mounted && widget.app.signedIn) {
      await widget.app.refreshCustomer();
      await _refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final customer = widget.app.customer!;
    final firstName = customer.fullName.split(' ').first;
    return Scaffold(
      appBar: AppBar(
        title: Text('Bonjour $firstName'),
        actions: [
          IconButton(
            tooltip: 'Se déconnecter',
            icon: const Icon(Icons.logout),
            onPressed: () => widget.app.signOut(),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
          children: [
            ErrorBanner(error),
            _BalanceCard(balance: balance, limits: limits),
            const SizedBox(height: 16),
            Row(children: [
              Expanded(
                child: _Action(
                  icon: Icons.add_circle_outline,
                  label: 'Recharger',
                  onTap: () => _open(TopupScreen(app: widget.app)),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _Action(
                  icon: Icons.send_outlined,
                  label: 'Envoyer',
                  onTap: () => _open(TransferScreen(app: widget.app, limits: limits)),
                ),
              ),
            ]),
            if (customer.kycLevel < 2) ...[
              const SizedBox(height: 16),
              _KycBanner(onTap: () => _open(KycScreen(app: widget.app, photos: widget.photos))),
            ],
            if (limits?.outgoingBlockedUntil != null) ...[
              const SizedBox(height: 16),
              const _Notice('Votre PIN a été changé. Par sécurité, les envois reprennent 24 heures après.'),
            ],
            const SizedBox(height: 28),
            const Text('Derniers mouvements', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            if (balance != null && entries.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Text('Aucun mouvement pour l\'instant. Commencez par une recharge.', style: TextStyle(color: Brand.muted)),
              ),
            for (final e in entries) _EntryTile(e),
          ],
        ),
      ),
    );
  }
}

class _BalanceCard extends StatelessWidget {
  const _BalanceCard({required this.balance, required this.limits});

  final int? balance;
  final Limits? limits;

  @override
  Widget build(BuildContext context) {
    final l = limits;
    final used = l == null || l.dailyOutMinor == 0 ? 0.0 : 1 - l.dailyOutRemainingMinor / l.dailyOutMinor;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(color: Brand.ink, borderRadius: BorderRadius.circular(20)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Expanded(child: Text('Solde disponible', style: TextStyle(color: Colors.white70, fontSize: 14))),
          if (l != null)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(color: Brand.ochre, borderRadius: BorderRadius.circular(99)),
              child: Text('Niveau ${l.kycLevel}', style: const TextStyle(color: Brand.ink, fontWeight: FontWeight.w700, fontSize: 12)),
            ),
        ]),
        const SizedBox(height: 8),
        Text(
          balance == null ? '…' : formatUsd(balance!),
          key: const ValueKey('balance'),
          style: const TextStyle(color: Colors.white, fontSize: 36, fontWeight: FontWeight.w800),
        ),
        if (l != null) ...[
          const SizedBox(height: 20),
          ClipRRect(
            borderRadius: BorderRadius.circular(99),
            child: LinearProgressIndicator(
              value: used.clamp(0, 1),
              minHeight: 6,
              backgroundColor: Colors.white12,
              color: Brand.ochre,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Encore ${formatUsd(l.dailyOutRemainingMinor)} à envoyer aujourd\'hui sur ${formatUsd(l.dailyOutMinor)}',
            style: const TextStyle(color: Colors.white70, fontSize: 13),
          ),
        ],
      ]),
    );
  }
}

class _Action extends StatelessWidget {
  const _Action({required this.icon, required this.label, required this.onTap});

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 18),
            child: Column(children: [
              Icon(icon, color: Brand.ink, size: 28),
              const SizedBox(height: 6),
              Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
            ]),
          ),
        ),
      );
}

class _KycBanner extends StatelessWidget {
  const _KycBanner({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
        color: const Color(0xFFFFF4E0),
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: onTap,
          child: const Padding(
            padding: EdgeInsets.all(16),
            child: Row(children: [
              Icon(Icons.verified_user_outlined, color: Brand.ink),
              SizedBox(width: 12),
              Expanded(
                child: Text('Passez au niveau 2 pour envoyer jusqu\'à 4 000 \$ par opération.',
                    style: TextStyle(fontWeight: FontWeight.w600)),
              ),
              Icon(Icons.chevron_right),
            ]),
          ),
        ),
      );
}

class _Notice extends StatelessWidget {
  const _Notice(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: const Color(0xFFEDE8E3), borderRadius: BorderRadius.circular(12)),
        child: Text(text),
      );
}

class _EntryTile extends StatelessWidget {
  const _EntryTile(this.e);

  final Entry e;

  @override
  Widget build(BuildContext context) {
    final d = e.createdAt.toLocal();
    final date = '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')} '
        '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        backgroundColor: e.isCredit ? const Color(0xFFE3F1EA) : const Color(0xFFF1ECE7),
        child: Icon(e.isCredit ? Icons.south_west : Icons.north_east, color: e.isCredit ? Brand.success : Brand.ink, size: 20),
      ),
      title: Text(e.isCredit ? 'Argent reçu' : 'Argent sorti'),
      subtitle: Text(date),
      trailing: Text(
        '${e.isCredit ? '+' : '-'}${formatUsd(e.amountMinor)}',
        style: TextStyle(fontWeight: FontWeight.w700, color: e.isCredit ? Brand.success : Brand.ink),
      ),
    );
  }
}
