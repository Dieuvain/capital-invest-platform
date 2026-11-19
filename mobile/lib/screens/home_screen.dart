import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'kyc_screen.dart';
import 'money_screens.dart';

/// Écran principal : en-tête ocre à onglets, feuille arrondie, barre du bas avec bouton central de paiement.
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
  int tab = 0; // Aperçu, Mouvements, Plafonds
  int section = 0; // Mes finances, Produits

  static const tabs = ['Aperçu', 'Mouvements', 'Plafonds'];

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final api = widget.app.api;
    await run(widget.app, () async {
      final results = await Future.wait([api.balance(), api.limits(), api.entries(limit: 30)]);
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

  void _topup() => _open(TopupScreen(app: widget.app));
  void _transfer() => _open(TransferScreen(app: widget.app, limits: limits));
  void _kyc() => _open(KycScreen(app: widget.app, photos: widget.photos));

  void _payments() => showModalBottomSheet<void>(
        context: context,
        backgroundColor: Colors.white,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
        builder: (sheet) => SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Center(
                child: Container(
                    width: 40, height: 4, decoration: BoxDecoration(color: const Color(0xFFDDD8D2), borderRadius: BorderRadius.circular(2))),
              ),
              const SizedBox(height: 20),
              const Text('Paiements', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
              const SizedBox(height: 16),
              _SheetAction(
                icon: Icons.add_rounded,
                title: 'Recharger',
                text: 'Depuis M-Pesa, Airtel Money ou Orange Money',
                onTap: () {
                  Navigator.of(sheet).pop();
                  _topup();
                },
              ),
              const SizedBox(height: 12),
              _SheetAction(
                icon: Icons.send_rounded,
                title: 'Envoyer',
                text: 'À un autre client, avec son numéro',
                onTap: () {
                  Navigator.of(sheet).pop();
                  _transfer();
                },
              ),
            ]),
          ),
        ),
      );

  void _profile() {
    final c = widget.app.customer!;
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
      builder: (sheet) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 20),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            CircleAvatar(
              radius: 32,
              backgroundColor: Brand.ochre,
              child: Text(c.fullName.isEmpty ? '?' : c.fullName[0].toUpperCase(),
                  style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800, color: Brand.ink)),
            ),
            const SizedBox(height: 12),
            Text(c.fullName, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
            Text('${c.phone} · Niveau ${c.kycLevel}', style: const TextStyle(color: Brand.muted)),
            const SizedBox(height: 24),
            OutlinedButton.icon(
              icon: const Icon(Icons.logout),
              label: const Text('Se déconnecter'),
              onPressed: () {
                Navigator.of(sheet).pop();
                widget.app.signOut();
              },
            ),
          ]),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final customer = widget.app.customer!;
    final firstName = customer.fullName.split(' ').first;
    final width = MediaQuery.sizeOf(context).width;
    return Scaffold(
      backgroundColor: Brand.paper,
      extendBody: true,
      body: Container(
        decoration: const BoxDecoration(gradient: Brand.headerGradient),
        child: SafeArea(
          bottom: false,
          child: Column(children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
              child: Row(children: [
                HeaderButton(icon: Icons.person_outline_rounded, tooltip: 'Mon profil', onTap: _profile),
                const SizedBox(width: 14),
                Expanded(
                  child: Text(
                    section == 0 ? 'Bonjour $firstName' : 'Produits',
                    style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Brand.ink),
                  ),
                ),
                HeaderButton(
                  icon: Icons.notifications_none_rounded,
                  tooltip: 'Alertes',
                  dot: _alerts(customer).isNotEmpty,
                  onTap: () => setState(() {
                    section = 0;
                    tab = 0;
                  }),
                ),
              ]),
            ),
            if (section == 0)
              Padding(
                padding: const EdgeInsets.only(top: 22),
                child: Row(children: [
                  for (var i = 0; i < tabs.length; i++)
                    Expanded(
                      child: InkWell(
                        onTap: () => setState(() => tab = i),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          child: AnimatedDefaultTextStyle(
                            duration: const Duration(milliseconds: 200),
                            style: TextStyle(
                              fontFamily: 'HankenGrotesk',
                              fontSize: tab == i ? 18 : 16,
                              fontWeight: tab == i ? FontWeight.w800 : FontWeight.w600,
                              color: tab == i ? Brand.ink : Brand.ink.withValues(alpha: 0.55),
                            ),
                            child: Text(tabs[i], textAlign: TextAlign.center),
                          ),
                        ),
                      ),
                    ),
                ]),
              )
            else
              const SizedBox(height: 24),
            Expanded(
              child: TweenAnimationBuilder<double>(
                tween: Tween(end: section == 0 ? width * (tab + 0.5) / tabs.length : -1),
                duration: const Duration(milliseconds: 260),
                curve: Curves.easeOutCubic,
                builder: (context, x, child) => ClipPath(
                  clipper: NotchedSheetClipper(notchX: x < 0 ? null : x),
                  child: Stack(children: [
                    child!,
                    if (x >= 0)
                      Positioned(
                        left: x - 3,
                        top: 4,
                        child: Container(width: 6, height: 6, decoration: const BoxDecoration(color: Brand.ochreDeep, shape: BoxShape.circle)),
                      ),
                  ]),
                ),
                child: Container(
                  color: Brand.paper,
                  child: RefreshIndicator(
                    onRefresh: _refresh,
                    child: section == 1 ? _products(customer) : [_overview, _movements, _limits][tab](customer),
                  ),
                ),
              ),
            ),
          ]),
        ),
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerDocked,
      floatingActionButton: SizedBox(
        width: 64,
        height: 64,
        child: FloatingActionButton(
          key: const ValueKey('pay-button'),
          tooltip: 'Paiements',
          onPressed: _payments,
          backgroundColor: Colors.white,
          foregroundColor: Brand.ink,
          elevation: 6,
          shape: const CircleBorder(),
          child: const Icon(Icons.swap_horiz_rounded, size: 30),
        ),
      ),
      bottomNavigationBar: BottomAppBar(
        color: Colors.white,
        elevation: 12,
        shadowColor: const Color(0x33231F20),
        shape: const CircularNotchedRectangle(),
        notchMargin: 8,
        height: 76,
        padding: EdgeInsets.zero,
        child: Row(children: [
          Expanded(child: _NavItem(icon: Icons.account_balance_wallet_outlined, label: 'Mes finances', active: section == 0, onTap: () => setState(() => section = 0))),
          const Expanded(
            child: Padding(
              padding: EdgeInsets.only(top: 34),
              child: Text('Paiements', textAlign: TextAlign.center, style: TextStyle(fontSize: 12, color: Brand.muted)),
            ),
          ),
          Expanded(child: _NavItem(icon: Icons.star_outline_rounded, label: 'Produits', active: section == 1, onTap: () => setState(() => section = 1))),
        ]),
      ),
    );
  }

  /// Alertes de l'aperçu, en cartes qui défilent.
  List<_Alert> _alerts(Customer c) => [
        if (limits?.outgoingBlockedUntil != null)
          _Alert(Icons.lock_clock_outlined, 'PIN changé', 'Envois suspendus 24 h par sécurité', null, null),
        if (c.kycLevel < 2)
          _Alert(Icons.verified_user_outlined, 'Passez au niveau 2', 'Envoyez jusqu\'à', '4 000,00 \$', _kyc),
        if (balance == 0) _Alert(Icons.add_card_outlined, 'Première recharge', 'Depuis votre mobile money', null, _topup),
      ];

  Widget _overview(Customer c) {
    final l = limits;
    final alerts = _alerts(c);
    return ListView(
      padding: const EdgeInsets.fromLTRB(0, 34, 0, 120),
      children: [
        Padding(padding: const EdgeInsets.symmetric(horizontal: 20), child: ErrorBanner(error)),
        if (alerts.isNotEmpty)
          SizedBox(
            height: 132,
            child: ListView.separated(
              clipBehavior: Clip.none,
              padding: const EdgeInsets.symmetric(horizontal: 20),
              scrollDirection: Axis.horizontal,
              itemCount: alerts.length,
              separatorBuilder: (_, _) => const SizedBox(width: 14),
              itemBuilder: (_, i) => _AlertCard(alerts[i], width: alerts.length == 1 ? MediaQuery.sizeOf(context).width - 40 : 260),
            ),
          ),
        const SizedBox(height: 26),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Mon solde', style: TextStyle(color: Brand.muted, fontSize: 14)),
                const SizedBox(height: 4),
                FittedBox(
                  alignment: Alignment.centerLeft,
                  fit: BoxFit.scaleDown,
                  child: Amount(balance == null ? '…' : formatUsd(balance!), key: const ValueKey('balance')),
                ),
              ]),
            ),
            if (l != null)
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const Text('Envoi possible aujourd\'hui', style: TextStyle(color: Brand.muted, fontSize: 14)),
                  const SizedBox(height: 4),
                  FittedBox(
                    alignment: Alignment.centerLeft,
                    fit: BoxFit.scaleDown,
                    child: Amount(formatUsd(l.dailyOutRemainingMinor), weight: FontWeight.w400),
                  ),
                ]),
              ),
          ]),
        ),
        const SizedBox(height: 30),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 24),
          child: Text('Comptes', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
        ),
        const SizedBox(height: 14),
        SizedBox(
          height: 214,
          child: ListView(
            clipBehavior: Clip.none,
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 34),
            scrollDirection: Axis.horizontal,
            children: [
              SoftCard(
                width: 180,
                onTap: () => setState(() => tab = 1),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  const IconTile(Icons.account_balance_wallet_outlined),
                  const Spacer(),
                  const Text('Portefeuille USD', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                  const SizedBox(height: 6),
                  Text(balance == null ? '…' : formatUsd(balance!), style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800)),
                  if (l != null)
                    Text('Niveau ${l.kycLevel}', style: const TextStyle(color: Brand.muted, fontSize: 13)),
                ]),
              ),
              const SizedBox(width: 14),
              const SoftCard(
                width: 180,
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  IconTile(Icons.credit_card_outlined, muted: true),
                  Spacer(),
                  Text('Carte prépayée', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                  SizedBox(height: 6),
                  Text('Bientôt disponible', style: TextStyle(color: Brand.muted, fontSize: 14)),
                ]),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Row(children: [
            const Expanded(child: Text('Derniers mouvements', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800))),
            if (entries.length > 3) TextButton(onPressed: () => setState(() => tab = 1), child: const Text('Tout voir')),
          ]),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: _EntriesCard(entries.take(3).toList(), loaded: balance != null),
        ),
      ],
    );
  }

  Widget _movements(Customer c) => ListView(
        padding: const EdgeInsets.fromLTRB(20, 34, 20, 120),
        children: [
          ErrorBanner(error),
          _EntriesCard(entries, loaded: balance != null),
        ],
      );

  Widget _limits(Customer c) {
    final l = limits;
    if (l == null) return ListView(children: [const SizedBox(height: 80), ErrorBanner(error)]);
    final used = l.dailyOutMinor - l.dailyOutRemainingMinor;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 34, 20, 120),
      children: [
        SoftCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const IconTile(Icons.speed_rounded, size: 44),
              const SizedBox(width: 12),
              Expanded(child: Text('Niveau ${l.kycLevel}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800))),
            ]),
            const SizedBox(height: 20),
            _Gauge('Envoyé aujourd\'hui', used, l.dailyOutMinor),
            const SizedBox(height: 18),
            _Gauge('Solde', balance ?? 0, l.maxBalanceMinor),
            const SizedBox(height: 18),
            Row(children: [
              const Expanded(child: Text('Par opération', style: TextStyle(color: Brand.muted))),
              Text(formatUsd(l.perTransactionMinor), style: const TextStyle(fontWeight: FontWeight.w700)),
            ]),
          ]),
        ),
        if (c.kycLevel < 2) ...[
          const SizedBox(height: 16),
          FilledButton(onPressed: _kyc, child: const Text('Relever mes plafonds')),
        ],
      ],
    );
  }

  Widget _products(Customer c) => ListView(
        padding: const EdgeInsets.fromLTRB(20, 34, 20, 120),
        children: [
          _ProductTile(
            icon: Icons.verified_user_outlined,
            title: 'Niveau 2',
            text: c.kycLevel >= 2 ? 'Actif : plafonds relevés' : 'Pièce d\'identité et selfie, plafonds x20',
            onTap: _kyc,
          ),
          const SizedBox(height: 12),
          const _ProductTile(icon: Icons.credit_card_outlined, title: 'Carte prépayée Visa', text: 'Bientôt disponible'),
          const SizedBox(height: 12),
          const _ProductTile(icon: Icons.savings_outlined, title: 'Épargne', text: 'Bientôt disponible'),
          const SizedBox(height: 12),
          const _ProductTile(icon: Icons.handshake_outlined, title: 'Crédit', text: 'Bientôt disponible'),
        ],
      );
}

class _Alert {
  _Alert(this.icon, this.title, this.text, this.amount, this.onTap);

  final IconData icon;
  final String title;
  final String text;
  final String? amount;
  final VoidCallback? onTap;
}

class _AlertCard extends StatelessWidget {
  const _AlertCard(this.a, {required this.width});

  final _Alert a;
  final double width;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 4, bottom: 12),
        child: SoftCard(
          width: width,
          onTap: a.onTap,
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(a.title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                Text(a.text, style: const TextStyle(color: Brand.muted, fontSize: 14)),
                const Spacer(),
                if (a.amount != null) Text(a.amount!, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800)),
              ]),
            ),
            CircleAvatar(
              radius: 24,
              backgroundColor: Brand.ochre,
              child: Icon(a.icon, color: Brand.ink),
            ),
          ]),
        ),
      );
}

class _EntriesCard extends StatelessWidget {
  const _EntriesCard(this.entries, {required this.loaded});

  final List<Entry> entries;
  final bool loaded;

  @override
  Widget build(BuildContext context) {
    if (loaded && entries.isEmpty) {
      return const SoftCard(child: Text('Aucun mouvement pour l\'instant. Commencez par une recharge.', style: TextStyle(color: Brand.muted)));
    }
    return SoftCard(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Column(children: [
        for (var i = 0; i < entries.length; i++) ...[
          if (i > 0) const Divider(height: 1, color: Color(0xFFF0ECE7)),
          _EntryTile(entries[i]),
        ],
      ]),
    );
  }
}

class _EntryTile extends StatelessWidget {
  const _EntryTile(this.e);

  final Entry e;

  @override
  Widget build(BuildContext context) {
    final d = e.createdAt.toLocal();
    final date = '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')} '
        '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(children: [
        Container(
          width: 42,
          height: 42,
          decoration: BoxDecoration(
            color: e.isCredit ? const Color(0xFFE3F1EA) : const Color(0xFFF4EFE9),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Icon(e.isCredit ? Icons.south_west_rounded : Icons.north_east_rounded,
              color: e.isCredit ? Brand.success : Brand.ink, size: 20),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(e.isCredit ? 'Argent reçu' : 'Argent sorti', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            Text(date, style: const TextStyle(color: Brand.muted, fontSize: 13)),
          ]),
        ),
        Text(
          '${e.isCredit ? '+' : '-'}${formatUsd(e.amountMinor)}',
          style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15, color: e.isCredit ? Brand.success : Brand.ink),
        ),
      ]),
    );
  }
}

class _Gauge extends StatelessWidget {
  const _Gauge(this.label, this.value, this.max);

  final String label;
  final int value;
  final int max;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(label, style: const TextStyle(color: Brand.muted))),
          Text('${formatUsd(value)} / ${formatUsd(max)}', style: const TextStyle(fontWeight: FontWeight.w700)),
        ]),
        const SizedBox(height: 8),
        ClipRRect(
          borderRadius: BorderRadius.circular(99),
          child: LinearProgressIndicator(
            value: max == 0 ? 0 : (value / max).clamp(0, 1).toDouble(),
            minHeight: 8,
            backgroundColor: const Color(0xFFF0ECE7),
            color: Brand.ochreDeep,
          ),
        ),
      ]);
}

class _NavItem extends StatelessWidget {
  const _NavItem({required this.icon, required this.label, required this.active, required this.onTap});

  final IconData icon;
  final String label;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkResponse(
        onTap: onTap,
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            width: 46,
            height: 36,
            decoration: BoxDecoration(
              color: active ? const Color(0xFFFFEBC8) : Colors.transparent,
              borderRadius: BorderRadius.circular(18),
            ),
            child: Icon(icon, color: active ? Brand.ochreDeep : Brand.ink),
          ),
          const SizedBox(height: 2),
          Text(label,
              style: TextStyle(
                  fontSize: 12, fontWeight: active ? FontWeight.w700 : FontWeight.w500, color: active ? Brand.ochreDeep : Brand.muted)),
        ]),
      );
}

class _SheetAction extends StatelessWidget {
  const _SheetAction({required this.icon, required this.title, required this.text, required this.onTap});

  final IconData icon;
  final String title;
  final String text;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => SoftCard(
        onTap: onTap,
        child: Row(children: [
          IconTile(icon, size: 48),
          const SizedBox(width: 14),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
              Text(text, style: const TextStyle(color: Brand.muted, fontSize: 13)),
            ]),
          ),
          const Icon(Icons.chevron_right),
        ]),
      );
}

class _ProductTile extends StatelessWidget {
  const _ProductTile({required this.icon, required this.title, required this.text, this.onTap});

  final IconData icon;
  final String title;
  final String text;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => SoftCard(
        onTap: onTap,
        child: Row(children: [
          IconTile(icon, size: 48, muted: onTap == null),
          const SizedBox(width: 14),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
              Text(text, style: const TextStyle(color: Brand.muted, fontSize: 13)),
            ]),
          ),
          if (onTap != null) const Icon(Icons.chevron_right),
        ]),
      );
}
