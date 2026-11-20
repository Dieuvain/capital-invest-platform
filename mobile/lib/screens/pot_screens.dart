import 'package:material_ui/material_ui.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Barre d'avancement vers l'objectif, ou rien quand la cagnotte n'en a pas.
class _Progress extends StatelessWidget {
  const _Progress(this.pot);

  final Pot pot;

  @override
  Widget build(BuildContext context) {
    final p = pot.progressPercent;
    if (p == null) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 10),
        ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: LinearProgressIndicator(
            value: p / 100,
            minHeight: 10,
            backgroundColor: context.pal.surfaceAlt,
            color: p >= 100 ? Brand.success : Brand.ochre,
          ),
        ),
        const SizedBox(height: 6),
        Text('$p % de ${formatUsd(pot.targetMinor!)}', style: TextStyle(color: context.pal.muted, fontSize: 13)),
      ],
    );
  }
}

String _people(int n) => n == 0 ? 'Aucune participation' : '$n participant${n > 1 ? 's' : ''}';

/// Mes cagnottes, et l'entrée pour participer à celle d'un proche avec son code.
class PotsScreen extends StatefulWidget {
  const PotsScreen({super.key, required this.app});

  final AppState app;

  @override
  State<PotsScreen> createState() => _PotsScreenState();
}

class _PotsScreenState extends State<PotsScreen> with ApiAction {
  final code = TextEditingController();
  List<Pot>? pots;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, () => widget.app.api.myPots());
    if (l != null && mounted) setState(() => pots = l);
  }

  Future<void> _push(Widget screen) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => screen));
    if (mounted) await _load();
  }

  /// Créée : on l'ouvre tout de suite pour partager son code.
  Future<void> _create() async {
    final p = await Navigator.of(context).push<Pot>(MaterialPageRoute(builder: (_) => CreatePotScreen(app: widget.app)));
    if (p != null && mounted) await _push(PotScreen(app: widget.app, pot: p, justCreated: true));
  }

  void _join() {
    final c = potCodeFrom(code.text);
    if (c == null) return setState(() => error = 'Le code a 6 caractères, par exemple K7M 4QX.');
    setState(() => error = null);
    _push(ContributeScreen(app: widget.app, code: c));
  }

  @override
  Widget build(BuildContext context) {
    final list = pots;
    return FormPage(
      title: 'Cagnottes',
      subtitle: 'Collectez l\'argent de vos proches pour un deuil, un mariage ou une cotisation. Participer ne coûte rien.',
      children: [
        ErrorBanner(error),
        SoftCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Participer à une cagnotte', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      key: const ValueKey('pot-code'),
                      controller: code,
                      textCapitalization: TextCapitalization.characters,
                      maxLength: 7,
                      onSubmitted: (_) => _join(),
                      decoration: const InputDecoration(labelText: 'Code reçu', hintText: 'K7M 4QX', counterText: ''),
                    ),
                  ),
                  const SizedBox(width: 10),
                  // Largeur fixe : le thème donne aux boutons toute la largeur disponible.
                  SizedBox(width: 110, child: FilledButton(key: const ValueKey('pot-join'), onPressed: _join, child: const Text('Ouvrir'))),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        FilledButton.icon(
          key: const ValueKey('pot-new'),
          onPressed: _create,
          icon: const Icon(Icons.add_rounded),
          label: const Text('Créer une cagnotte'),
        ),
        const SizedBox(height: 20),
        if (list == null && error == null) const Center(child: CircularProgressIndicator()),
        if (list != null && list.isNotEmpty) ...[
          const Text('Mes cagnottes', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 18)),
          const SizedBox(height: 10),
        ],
        for (final p in list ?? const <Pot>[])
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: SoftCard(
              key: ValueKey('pot-${p.code}'),
              onTap: () => _push(PotScreen(app: widget.app, pot: p)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      IconTile(Icons.volunteer_activism_outlined, accent: p.isOpen),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(p.title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                            Text(
                              p.isOpen ? '${_people(p.contributors)} · code ${p.spacedCode}' : 'Clôturée, versée sur votre solde',
                              style: TextStyle(color: context.pal.muted, fontSize: 13),
                            ),
                          ],
                        ),
                      ),
                      Text(formatUsd(p.collectedMinor), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                    ],
                  ),
                  if (p.isOpen) _Progress(p),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

/// Créer une cagnotte : un nom, un message facultatif, un objectif facultatif.
class CreatePotScreen extends StatefulWidget {
  const CreatePotScreen({super.key, required this.app});

  final AppState app;

  @override
  State<CreatePotScreen> createState() => _CreatePotScreenState();
}

class _CreatePotScreenState extends State<CreatePotScreen> with ApiAction {
  final title = TextEditingController();
  final description = TextEditingController();
  final target = TextEditingController();

  Future<void> _create() async {
    final t = title.text.trim();
    if (t.isEmpty) return setState(() => error = 'Donnez un nom à la cagnotte, par exemple « Deuil de Papa Mbuyi ».');
    int? targetMinor;
    if (target.text.trim().isNotEmpty) {
      targetMinor = parseUsd(target.text);
      if (targetMinor == null) return setState(() => error = 'Entrez un objectif, par exemple 500, ou laissez vide.');
    }
    final p = await run(
      widget.app,
      () => widget.app.api.createPot(title: t, description: description.text.trim().isEmpty ? null : description.text.trim(), targetMinor: targetMinor),
    );
    if (p != null && mounted) Navigator.of(context).pop(p);
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: 'Nouvelle cagnotte',
    subtitle: 'Vos proches participent avec un code ou un QR. L\'argent arrive sur votre solde quand vous clôturez.',
    children: [
      ErrorBanner(error),
      TextField(
        key: const ValueKey('pot-title'),
        controller: title,
        maxLength: 40,
        textCapitalization: TextCapitalization.sentences,
        decoration: const InputDecoration(labelText: 'Nom de la cagnotte', hintText: 'Deuil de Papa Mbuyi'),
      ),
      TextField(
        key: const ValueKey('pot-description'),
        controller: description,
        maxLength: 140,
        maxLines: 2,
        textCapitalization: TextCapitalization.sentences,
        decoration: const InputDecoration(labelText: 'Message aux participants (facultatif)', hintText: 'Pour les funérailles de samedi'),
      ),
      TextField(
        key: const ValueKey('pot-target'),
        controller: target,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        decoration: const InputDecoration(labelText: 'Objectif (facultatif)', suffixText: '\$'),
      ),
      const SizedBox(height: 20),
      BusyButton(label: 'Créer la cagnotte', busy: busy, onPressed: _create),
    ],
  );
}

/// Ma cagnotte : le code et le QR à partager, les participations, et la clôture.
class PotScreen extends StatefulWidget {
  const PotScreen({super.key, required this.app, required this.pot, this.justCreated = false});

  final AppState app;
  final Pot pot;
  final bool justCreated;

  @override
  State<PotScreen> createState() => _PotScreenState();
}

class _PotScreenState extends State<PotScreen> with ApiAction {
  late Pot pot = widget.pot;

  @override
  void initState() {
    super.initState();
    if (!widget.justCreated) _load();
  }

  Future<void> _load() async {
    final p = await run(widget.app, () => widget.app.api.pot(pot.code));
    if (p != null && mounted) setState(() => pot = p);
  }

  Future<void> _share() => widget.app.share(
    'Participez à ma cagnotte « ${pot.title} » sur Capital Invest. Dans l\'app : Plus, puis Cagnottes, '
        'et entrez le code ${pot.spacedCode}. ${pot.shareLink}',
    pot.title,
  );

  Future<void> _close() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Clôturer la cagnotte ?'),
        content: Text(
          pot.collectedMinor > 0
              ? '${formatUsd(pot.collectedMinor)} passent sur votre solde. Personne ne pourra plus participer.'
              : 'Personne n\'a encore participé. Personne ne pourra plus participer.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(false), child: const Text('Non')),
          FilledButton(onPressed: () => Navigator.of(c).pop(true), child: const Text('Clôturer')),
        ],
      ),
    );
    if (ok != true) return;
    final p = await run(widget.app, () => widget.app.api.closePot(pot.id));
    if (p != null && mounted) setState(() => pot = p);
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: pot.title,
    subtitle: pot.description,
    children: [
      ErrorBanner(error),
      SoftCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(pot.isOpen ? 'Collecté' : 'Versé sur votre solde', style: TextStyle(color: context.pal.muted)),
            const SizedBox(height: 4),
            Amount(formatUsd(pot.paidOutMinor ?? pot.collectedMinor), key: const ValueKey('pot-collected')),
            Text(_people(pot.contributors), style: TextStyle(color: context.pal.muted, fontSize: 13)),
            _Progress(pot),
          ],
        ),
      ),
      if (pot.isOpen) ...[
        const SizedBox(height: 16),
        SoftCard(
          child: Column(
            children: [
              Text('Code à partager', style: TextStyle(color: context.pal.muted)),
              const SizedBox(height: 4),
              Text(
                pot.spacedCode,
                key: const ValueKey('pot-share-code'),
                style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w800, letterSpacing: 3),
              ),
              const SizedBox(height: 14),
              // Fond blanc même en mode sombre : les lecteurs de QR lisent mieux le noir sur blanc.
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18)),
                child: QrImageView(
                  data: pot.shareLink,
                  size: 170,
                  backgroundColor: Colors.white,
                  eyeStyle: const QrEyeStyle(eyeShape: QrEyeShape.square, color: Brand.ink),
                  dataModuleStyle: const QrDataModuleStyle(dataModuleShape: QrDataModuleShape.square, color: Brand.ink),
                ),
              ),
              const SizedBox(height: 10),
              Text(
                'À scanner avec « Payer » dans l\'app Capital Invest.',
                textAlign: TextAlign.center,
                style: TextStyle(color: context.pal.muted, fontSize: 13),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                key: const ValueKey('pot-share'),
                onPressed: _share,
                icon: const Icon(Icons.share_rounded),
                label: const Text('Partager par WhatsApp ou SMS'),
              ),
            ],
          ),
        ),
      ],
      const SizedBox(height: 20),
      if (pot.contributions.isNotEmpty) ...[
        const Text('Participations', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 18)),
        const SizedBox(height: 8),
        for (final c in pot.contributions)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(c.name ?? 'Un proche', style: const TextStyle(fontWeight: FontWeight.w600)),
                      if (c.message != null) Text('« ${c.message} »', style: TextStyle(color: context.pal.muted, fontSize: 13)),
                      Text(formatWhen(c.createdAt), style: TextStyle(color: context.pal.muted, fontSize: 12)),
                    ],
                  ),
                ),
                if (c.amountMinor != null) Text(formatUsd(c.amountMinor!), style: const TextStyle(fontWeight: FontWeight.w700)),
              ],
            ),
          ),
        const SizedBox(height: 12),
      ],
      if (pot.isOpen && pot.mine)
        OutlinedButton(
          key: const ValueKey('pot-close'),
          onPressed: busy ? null : _close,
          child: Text(pot.collectedMinor > 0 ? 'Clôturer et recevoir ${formatUsd(pot.collectedMinor)}' : 'Clôturer la cagnotte'),
        ),
    ],
  );
}

/// Participer à la cagnotte d'un proche : on voit à qui on donne, puis on confirme avec son PIN.
class ContributeScreen extends StatefulWidget {
  const ContributeScreen({super.key, required this.app, required this.code});

  final AppState app;
  final String code;

  @override
  State<ContributeScreen> createState() => _ContributeScreenState();
}

class _ContributeScreenState extends State<ContributeScreen> with ApiAction {
  final amount = TextEditingController();
  final message = TextEditingController();
  final pin = TextEditingController();
  final String _key = newIdempotencyKey();
  bool hideName = false;
  Pot? pot;
  int? given;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final p = await run(widget.app, () => widget.app.api.pot(widget.code));
    if (p != null && mounted) setState(() => pot = p);
  }

  Future<void> _give() async {
    final a = parseUsd(amount.text);
    if (a == null || a <= 0) return setState(() => error = 'Entrez un montant, par exemple 10 ou 12,50.');
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final r = await run(
      widget.app,
      () => widget.app.api.contributeToPot(
        code: widget.code,
        amountMinor: a,
        pin: pin.text,
        idempotencyKey: _key,
        message: message.text.trim().isEmpty ? null : message.text.trim(),
        hideName: hideName,
      ),
    );
    pin.clear();
    if (r != null && mounted) {
      setState(() {
        pot = r;
        given = a;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = pot;
    if (p == null) {
      return FormPage(
        title: 'Cagnotte',
        children: [ErrorBanner(error), if (error == null) const Center(child: CircularProgressIndicator())],
      );
    }
    if (given != null) {
      return FormPage(
        title: 'Cagnotte',
        children: [
          const SizedBox(height: 24),
          const Icon(Icons.volunteer_activism, size: 72, color: Brand.success),
          const SizedBox(height: 16),
          Text(
            'Merci pour votre participation',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            '${formatUsd(given!)} versés à « ${p.title} ». ${p.ownerName} reçoit une notification. '
            'Total collecté : ${formatUsd(p.collectedMinor)}.',
            key: const ValueKey('pot-given'),
            textAlign: TextAlign.center,
            style: TextStyle(color: context.pal.muted),
          ),
          const SizedBox(height: 24),
          FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Terminé')),
        ],
      );
    }
    return FormPage(
      title: p.title,
      subtitle: 'Cagnotte organisée par ${p.ownerName}',
      children: [
        ErrorBanner(error),
        SoftCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (p.description != null) ...[
                Text(p.description!, style: const TextStyle(fontSize: 15)),
                const SizedBox(height: 12),
              ],
              Row(
                children: [
                  Expanded(child: Text(_people(p.contributors), style: TextStyle(color: context.pal.muted))),
                  Text(formatUsd(p.collectedMinor), key: const ValueKey('pot-total'), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18)),
                ],
              ),
              _Progress(p),
              if (p.myTotalMinor > 0) ...[
                const SizedBox(height: 8),
                Text('Vous avez déjà donné ${formatUsd(p.myTotalMinor)}.', style: TextStyle(color: context.pal.muted, fontSize: 13)),
              ],
            ],
          ),
        ),
        const SizedBox(height: 16),
        if (!p.isOpen)
          Text('Cette cagnotte est clôturée : on ne peut plus y participer.', style: TextStyle(color: context.pal.muted))
        else if (p.mine)
          Text('C\'est votre cagnotte : ouvrez-la depuis « Mes cagnottes » pour la partager.', style: TextStyle(color: context.pal.muted))
        else ...[
          TextField(
            key: const ValueKey('pot-amount'),
            controller: amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Montant', suffixText: '\$'),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('pot-message'),
            controller: message,
            maxLength: 80,
            textCapitalization: TextCapitalization.sentences,
            decoration: const InputDecoration(labelText: 'Un mot pour l\'organisateur (facultatif)', hintText: 'Courage à la famille'),
          ),
          SwitchListTile(
            key: const ValueKey('pot-hide-name'),
            contentPadding: EdgeInsets.zero,
            value: hideName,
            onChanged: (v) => setState(() => hideName = v),
            title: const Text('Cacher mon nom aux autres participants'),
            subtitle: const Text('L\'organisateur le voit toujours.'),
          ),
          const SizedBox(height: 8),
          Text('Sans frais. Votre participation compte dans votre plafond d\'envoi du jour.', style: TextStyle(color: context.pal.muted, fontSize: 13)),
          const SizedBox(height: 16),
          PinField(controller: pin, label: 'PIN pour confirmer', onSubmitted: (_) => _give()),
          const SizedBox(height: 20),
          BusyButton(label: 'Participer', busy: busy, onPressed: _give),
        ],
      ],
    );
  }
}
