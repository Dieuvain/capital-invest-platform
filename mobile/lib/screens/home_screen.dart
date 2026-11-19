import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/cards.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'kyc_screen.dart';
import 'money_screens.dart';

/// Tableau de bord : cartes empilées, actions rapides, promotions, mouvements ; barre du bas flottante.
class HomeScreen extends StatefulWidget {
  const HomeScreen({
    super.key,
    required this.app,
    required this.photos,
    this.promoInterval = const Duration(seconds: 5),
  });

  final AppState app;
  final PhotoSource photos;
  final Duration promoInterval;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with ApiAction {
  int? balance;
  Limits? limits;
  List<Entry> entries = const [];
  List<Promotion> promos = const [];
  int page = 0; // Accueil, Mouvements, Plafonds, Profil

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
    // Les promotions ne doivent jamais bloquer le tableau de bord.
    try {
      final p = await api.promotions();
      if (mounted) setState(() => promos = p);
    } on ApiException {
      if (mounted) setState(() => promos = const []);
    }
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
  void _withdraw() => _open(WithdrawScreen(app: widget.app, limits: limits));
  void _kyc() => _open(KycScreen(app: widget.app, photos: widget.photos));

  void _promoAction(Promotion p) => switch (p.action) {
    'KYC' => _kyc(),
    'TOPUP' => _topup(),
    'TRANSFER' => _transfer(),
    'LIMITS' => setState(() => page = 2),
    _ => null,
  };

  void _more() => showModalBottomSheet<void>(
    context: context,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
    builder: (sheet) {
      void go(VoidCallback f) {
        Navigator.of(sheet).pop();
        f();
      }

      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Plus', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
              const SizedBox(height: 8),
              _SheetRow(Icons.verified_user_outlined, 'Passer au niveau 2', () => go(_kyc)),
              _SheetRow(Icons.speed_rounded, 'Mes plafonds', () => go(() => setState(() => page = 2))),
              _SheetRow(Icons.receipt_long_outlined, 'Tous mes mouvements', () => go(() => setState(() => page = 1))),
              _SheetRow(Icons.person_outline_rounded, 'Mon profil', () => go(() => setState(() => page = 3))),
            ],
          ),
        ),
      );
    },
  );

  @override
  Widget build(BuildContext context) {
    final customer = widget.app.customer!;
    return Scaffold(
      body: Stack(
        children: [
          SafeArea(
            bottom: false,
            child: RefreshIndicator(
              onRefresh: _refresh,
              child: switch (page) {
                1 => _movementsPage(),
                2 => _limitsPage(customer),
                3 => _profilePage(customer),
                _ => _dashboard(customer),
              },
            ),
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: SafeArea(
              top: false,
              minimum: const EdgeInsets.only(bottom: 14),
              child: Center(
                child: _NavPill(index: page, onTap: (i) => setState(() => page = i)),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _header(Customer c, String title, String subtitle) {
    final initial = c.fullName.isEmpty ? '?' : c.fullName[0].toUpperCase();
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              RoundButton(icon: Icons.notes_rounded, tooltip: 'Menu', onTap: _more),
              const Spacer(),
              RoundButton(
                icon: Icons.notifications_none_rounded,
                tooltip: 'Alertes',
                dot: c.kycLevel < 2 || limits?.outgoingBlockedUntil != null,
                onTap: () => setState(() => page = 0),
              ),
              const SizedBox(width: 8),
              Tooltip(
                message: 'Mon profil',
                child: InkWell(
                  customBorder: const CircleBorder(),
                  onTap: () => setState(() => page = 3),
                  child: CircleAvatar(
                    radius: 24,
                    backgroundColor: Brand.ochre,
                    child: Text(
                      initial,
                      style: const TextStyle(color: Brand.ink, fontWeight: FontWeight.w800, fontSize: 18),
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 22),
          Text(title, style: const TextStyle(fontSize: 32, fontWeight: FontWeight.w700, letterSpacing: -0.6)),
          const SizedBox(height: 2),
          Text(subtitle, style: TextStyle(fontSize: 14, color: context.pal.muted)),
        ],
      ),
    );
  }

  Widget _dashboard(Customer c) {
    final firstName = c.fullName.split(' ').first;
    final last4 = c.phone.substring(c.phone.length - 4);
    return ListView(
      padding: const EdgeInsets.only(bottom: 120),
      children: [
        _header(c, 'Bonjour $firstName !', 'Gérons bien votre argent.'),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 22, 20, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              ErrorBanner(error),
              StackedCards(
                backLabel: 'Carte prépayée · bientôt',
                backNumber: '•••• •••• •••• ••••',
                label: 'Solde',
                amount: balance == null ? '…' : formatUsd(balance!),
                cornerLabel: 'Niveau',
                cornerValue: '${limits?.kycLevel ?? c.kycLevel}',
                ownerLabel: 'Titulaire · •••• $last4',
                owner: c.fullName,
                pillLabel: 'Recharger',
                onPill: _topup,
              ),
              const SizedBox(height: 16),
              SoftCard(
                padding: const EdgeInsets.symmetric(vertical: 18, horizontal: 8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    _QuickAction(Icons.north_east_rounded, 'Envoyer', _transfer),
                    _QuickAction(Icons.credit_card_rounded, 'Recharger', _topup),
                    _QuickAction(Icons.south_west_rounded, 'Retirer', _withdraw),
                    _QuickAction(Icons.more_horiz_rounded, 'Plus', _more),
                  ],
                ),
              ),
              if (promos.isNotEmpty) ...[
                const SizedBox(height: 16),
                PromoCarousel(promos: promos, interval: widget.promoInterval, onTap: _promoAction),
              ],
              const SizedBox(height: 16),
              _MovementsCard(
                entries: entries.take(4).toList(),
                loaded: balance != null,
                onSeeAll: entries.length > 4 ? () => setState(() => page = 1) : null,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _movementsPage() => ListView(
    padding: const EdgeInsets.only(bottom: 120),
    children: [
      _header(widget.app.customer!, 'Mouvements', 'Tout ce qui entre et sort de votre portefeuille.'),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 22, 20, 0),
        child: Column(
          children: [
            ErrorBanner(error),
            _MovementsCard(entries: entries, loaded: balance != null),
          ],
        ),
      ),
    ],
  );

  Widget _limitsPage(Customer c) {
    final l = limits;
    return ListView(
      padding: const EdgeInsets.only(bottom: 120),
      children: [
        _header(c, 'Plafonds', 'Ce que votre niveau vous permet.'),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 22, 20, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              ErrorBanner(error),
              if (l != null)
                SoftCard(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const IconTile(Icons.speed_rounded, accent: true),
                          const SizedBox(width: 12),
                          Text(
                            'Niveau ${l.kycLevel}',
                            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                          ),
                        ],
                      ),
                      const SizedBox(height: 22),
                      _Gauge('Envoyé aujourd\'hui', l.dailyOutMinor - l.dailyOutRemainingMinor, l.dailyOutMinor),
                      const SizedBox(height: 18),
                      _Gauge('Solde', balance ?? 0, l.maxBalanceMinor),
                      const SizedBox(height: 18),
                      Row(
                        children: [
                          Expanded(
                            child: Text('Par opération', style: TextStyle(color: context.pal.muted)),
                          ),
                          Text(formatUsd(l.perTransactionMinor), style: const TextStyle(fontWeight: FontWeight.w700)),
                        ],
                      ),
                    ],
                  ),
                ),
              if (c.kycLevel < 2) ...[
                const SizedBox(height: 16),
                FilledButton(onPressed: _kyc, child: const Text('Relever mes plafonds')),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _profilePage(Customer c) => ListView(
    padding: const EdgeInsets.only(bottom: 120),
    children: [
      _header(c, c.fullName, '${c.phone} · Niveau ${c.kycLevel}'),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 22, 20, 0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SoftCard(
              padding: const EdgeInsets.all(18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Apparence', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                  const SizedBox(height: 12),
                  SegmentedButton<ThemeMode>(
                    segments: const [
                      ButtonSegment(value: ThemeMode.system, label: Text('Auto')),
                      ButtonSegment(value: ThemeMode.light, label: Text('Clair')),
                      ButtonSegment(value: ThemeMode.dark, label: Text('Sombre')),
                    ],
                    selected: {widget.app.themeMode},
                    onSelectionChanged: (v) => widget.app.setThemeMode(v.first),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            SoftCard(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              child: Column(
                children: [
                  _SheetRow(
                    Icons.verified_user_outlined,
                    c.kycLevel >= 2 ? 'Niveau 2 actif' : 'Passer au niveau 2',
                    _kyc,
                  ),
                  _SheetRow(Icons.credit_card_outlined, 'Carte prépayée Visa · bientôt', null),
                  _SheetRow(Icons.savings_outlined, 'Épargne · bientôt', null),
                ],
              ),
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              icon: const Icon(Icons.logout_rounded),
              label: const Text('Se déconnecter'),
              onPressed: () => widget.app.signOut(),
            ),
          ],
        ),
      ),
    ],
  );
}

/// Bandeau de promotions de Capital Invest : défile tout seul, s'arrête quand on le touche.
class PromoCarousel extends StatefulWidget {
  const PromoCarousel({
    super.key,
    required this.promos,
    required this.onTap,
    this.interval = const Duration(seconds: 5),
  });

  final List<Promotion> promos;
  final ValueChanged<Promotion> onTap;
  final Duration interval;

  @override
  State<PromoCarousel> createState() => _PromoCarouselState();
}

class _PromoCarouselState extends State<PromoCarousel> {
  final controller = PageController();
  Timer? timer;
  int index = 0;

  @override
  void initState() {
    super.initState();
    _start();
  }

  void _start() {
    timer?.cancel();
    if (widget.promos.length < 2) return;
    timer = Timer.periodic(widget.interval, (_) {
      if (!controller.hasClients) return;
      final next = (index + 1) % widget.promos.length;
      controller.animateToPage(next, duration: const Duration(milliseconds: 450), curve: Curves.easeOutCubic);
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(
    children: [
      SizedBox(
        height: 148,
        child: Listener(
          onPointerDown: (_) => timer?.cancel(),
          onPointerUp: (_) => _start(),
          child: PageView.builder(
            key: const ValueKey('promos'),
            controller: controller,
            itemCount: widget.promos.length,
            onPageChanged: (i) => setState(() => index = i),
            itemBuilder: (_, i) => _PromoCard(widget.promos[i], onTap: () => widget.onTap(widget.promos[i])),
          ),
        ),
      ),
      if (widget.promos.length > 1) ...[
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < widget.promos.length; i++)
              AnimatedContainer(
                duration: const Duration(milliseconds: 250),
                margin: const EdgeInsets.symmetric(horizontal: 3),
                width: i == index ? 20 : 6,
                height: 6,
                decoration: BoxDecoration(
                  color: i == index ? Brand.ochreDeep : context.pal.muted.withValues(alpha: 0.35),
                  borderRadius: BorderRadius.circular(99),
                ),
              ),
          ],
        ),
      ],
    ],
  );
}

class _PromoCard extends StatelessWidget {
  const _PromoCard(this.p, {required this.onTap});

  final Promotion p;
  final VoidCallback onTap;

  static const icons = {
    'KYC': Icons.verified_user_rounded,
    'TOPUP': Icons.bolt_rounded,
    'TRANSFER': Icons.send_rounded,
    'LIMITS': Icons.speed_rounded,
  };

  @override
  Widget build(BuildContext context) {
    final (Gradient bg, Color fg) = switch (p.style) {
      'INK' => (const LinearGradient(colors: [Color(0xFF3A3233), Brand.ink]), Colors.white),
      'GREEN' => (const LinearGradient(colors: [Color(0xFF2BB673), Color(0xFF128A52)]), Colors.white),
      'SKY' => (const LinearGradient(colors: [Color(0xFF5AB8F0), Color(0xFF2A7FC4)]), Colors.white),
      _ => (Brand.ochreGradient, Brand.ink),
    };
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 2),
      child: Material(
        borderRadius: BorderRadius.circular(26),
        clipBehavior: Clip.antiAlias,
        child: Ink(
          decoration: BoxDecoration(gradient: bg),
          child: InkWell(
            onTap: p.action == null ? null : onTap,
            child: Stack(
              children: [
                Positioned(
                  right: -18,
                  bottom: -18,
                  child: Icon(
                    icons[p.action] ?? Icons.local_offer_rounded,
                    size: 120,
                    color: fg.withValues(alpha: 0.14),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
                        decoration: BoxDecoration(
                          color: fg.withValues(alpha: 0.14),
                          borderRadius: BorderRadius.circular(99),
                        ),
                        child: Text(
                          'PROMO',
                          style: TextStyle(color: fg, fontSize: 11, fontWeight: FontWeight.w800, letterSpacing: 1),
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        p.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: fg, fontSize: 19, fontWeight: FontWeight.w800),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        p.body,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: fg.withValues(alpha: 0.85), fontSize: 13.5, height: 1.3),
                      ),
                      const Spacer(),
                      if (p.ctaLabel != null)
                        Row(
                          children: [
                            Text(
                              p.ctaLabel!,
                              style: TextStyle(color: fg, fontWeight: FontWeight.w700, fontSize: 14),
                            ),
                            const SizedBox(width: 4),
                            Icon(Icons.arrow_forward_rounded, size: 18, color: fg),
                          ],
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _QuickAction extends StatelessWidget {
  const _QuickAction(this.icon, this.label, this.onTap);

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkResponse(
    key: ValueKey('quick-$label'),
    onTap: onTap,
    radius: 40,
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconTile(icon, size: 56),
        const SizedBox(height: 8),
        Text(label, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500)),
      ],
    ),
  );
}

class _MovementsCard extends StatelessWidget {
  const _MovementsCard({required this.entries, required this.loaded, this.onSeeAll});

  final List<Entry> entries;
  final bool loaded;
  final VoidCallback? onSeeAll;

  @override
  Widget build(BuildContext context) => SoftCard(
    padding: const EdgeInsets.fromLTRB(16, 14, 16, 6),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            const Expanded(
              child: Text('Derniers mouvements', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            ),
            if (onSeeAll != null)
              GestureDetector(
                onTap: onSeeAll,
                child: Text('Tout voir', style: TextStyle(color: context.pal.muted, fontSize: 13)),
              ),
          ],
        ),
        const SizedBox(height: 6),
        if (loaded && entries.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 18),
            child: Text(
              'Aucun mouvement pour l\'instant. Commencez par une recharge.',
              style: TextStyle(color: context.pal.muted),
            ),
          ),
        for (final e in entries) _EntryTile(e),
      ],
    ),
  );
}

class _EntryTile extends StatelessWidget {
  const _EntryTile(this.e);

  final Entry e;

  @override
  Widget build(BuildContext context) {
    final d = e.createdAt.toLocal();
    const months = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
    final date =
        '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')} · ${d.day} ${months[d.month - 1]} ${d.year}';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: [
          IconTile(e.isCredit ? Icons.south_west_rounded : Icons.north_east_rounded, size: 42),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  e.isCredit ? 'Argent reçu' : 'Argent sorti',
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                ),
                Text(date, style: TextStyle(color: context.pal.muted, fontSize: 12)),
              ],
            ),
          ),
          Text(
            '${e.isCredit ? '+' : '-'}${formatUsd(e.amountMinor)}',
            style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15, color: e.isCredit ? Brand.success : null),
          ),
        ],
      ),
    );
  }
}

class _Gauge extends StatelessWidget {
  const _Gauge(this.label, this.value, this.max);

  final String label;
  final int value;
  final int max;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Expanded(
            child: Text(label, style: TextStyle(color: context.pal.muted)),
          ),
          Text('${formatUsd(value)} / ${formatUsd(max)}', style: const TextStyle(fontWeight: FontWeight.w700)),
        ],
      ),
      const SizedBox(height: 8),
      ClipRRect(
        borderRadius: BorderRadius.circular(99),
        child: LinearProgressIndicator(
          value: max == 0 ? 0 : (value / max).clamp(0, 1).toDouble(),
          minHeight: 8,
          backgroundColor: context.pal.surfaceAlt,
          color: Brand.ochreDeep,
        ),
      ),
    ],
  );
}

class _SheetRow extends StatelessWidget {
  const _SheetRow(this.icon, this.label, this.onTap);

  final IconData icon;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => ListTile(
    contentPadding: const EdgeInsets.symmetric(horizontal: 8),
    leading: IconTile(icon, size: 40),
    title: Text(
      label,
      style: TextStyle(fontWeight: FontWeight.w600, color: onTap == null ? context.pal.muted : null),
    ),
    trailing: onTap == null ? null : const Icon(Icons.chevron_right_rounded),
    onTap: onTap,
  );
}

/// Barre du bas en pilule flottante : l'onglet actif est une pastille claire avec son nom.
class _NavPill extends StatelessWidget {
  const _NavPill({required this.index, required this.onTap});

  final int index;
  final ValueChanged<int> onTap;

  static const items = [
    (Icons.home_rounded, 'Accueil'),
    (Icons.account_balance_wallet_outlined, 'Mouvements'),
    (Icons.bar_chart_rounded, 'Plafonds'),
    (Icons.person_outline_rounded, 'Profil'),
  ];

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(6),
    decoration: BoxDecoration(
      color: context.pal.nav,
      borderRadius: BorderRadius.circular(99),
      boxShadow: const [BoxShadow(color: Color(0x33000000), blurRadius: 20, offset: Offset(0, 8))],
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < items.length; i++)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 3),
            child: Tooltip(
              message: items[i].$2,
              child: GestureDetector(
                onTap: () => onTap(i),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOutCubic,
                  height: 48,
                  padding: EdgeInsets.symmetric(horizontal: i == index ? 18 : 12),
                  decoration: BoxDecoration(
                    color: i == index ? Colors.white : Colors.white.withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(99),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(items[i].$1, color: i == index ? Brand.ink : Colors.white, size: 22),
                      if (i == index) ...[
                        const SizedBox(width: 8),
                        Text(
                          items[i].$2,
                          style: const TextStyle(color: Brand.ink, fontWeight: FontWeight.w700, fontSize: 15),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    ),
  );
}
