import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'block_screens.dart';
import 'closure_screen.dart';
import 'limit_screen.dart';

/// Sécurité du compte : changer son PIN, voir où le compte a été ouvert, déconnecter les autres téléphones.
class SecurityScreen extends StatefulWidget {
  const SecurityScreen({super.key, required this.app});

  final AppState app;

  @override
  State<SecurityScreen> createState() => _SecurityScreenState();
}

class _SecurityScreenState extends State<SecurityScreen> with ApiAction {
  List<DeviceSession>? sessions;
  String? done;

  /// Résultat de l'activation ou de la désactivation de l'empreinte, montré sous l'interrupteur.
  String? biometricNote;
  bool biometricNoteIsError = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, widget.app.api.deviceSessions);
    if (l != null && mounted) setState(() => sessions = l);
  }

  Future<void> _changePin() async {
    final changed = await Navigator.of(context)
        .push<bool>(MaterialPageRoute(builder: (_) => ChangePinScreen(app: widget.app)));
    if (changed == true && mounted) {
      setState(() => done = 'PIN changé. Vos autres téléphones ont été déconnectés.');
      await _load();
    }
  }

  /// Activer : PIN demandé dans une fenêtre, puis doigt posé. Désactiver : tout de suite.
  Future<void> _toggleBiometric(bool on) async {
    final app = widget.app;
    String? pin;
    if (on) {
      pin = await showDialog<String>(context: context, builder: (_) => const _PinDialog());
      if (pin == null || !mounted) return;
    }
    final result = await run(app, () async {
      if (!on) {
        await app.disableBiometric();
        return 'Empreinte désactivée. Le PIN sera demandé à chaque connexion.';
      }
      return await app.enableBiometric(pin!)
          ? 'Empreinte activée. Votre PIN reste demandé pour chaque envoi d\'argent.'
          : 'Empreinte non reconnue : rien n\'a changé. Réessayez.';
    });
    if (!mounted) return;
    // Le message reste sous l'interrupteur, là où le client regarde, et pas en haut de la page.
    setState(() {
      biometricNoteIsError = result == null;
      biometricNote = result ?? error;
      error = null;
    });
  }

  Future<void> _signOutOthers() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Déconnecter les autres téléphones ?'),
        content: const Text('Il faudra votre PIN pour se reconnecter sur ces téléphones.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(false), child: const Text('Annuler')),
          FilledButton(onPressed: () => Navigator.of(c).pop(true), child: const Text('Déconnecter')),
        ],
      ),
    );
    if (ok != true) return;
    final closed = await run(widget.app, widget.app.api.signOutOtherDevices);
    if (closed == null || !mounted) return;
    setState(() => done = closed == 1 ? '1 téléphone déconnecté.' : '$closed téléphones déconnectés.');
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final l = sessions;
    final othersActive = l?.any((s) => !s.current && s.active) ?? false;
    return FormPage(
      title: 'Sécurité',
      subtitle: 'Personne chez Capital Invest ne vous demandera jamais votre PIN ni un code SMS.',
      children: [
        ErrorBanner(error),
        if (done != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Row(
              children: [
                const Icon(Icons.check_circle, color: Brand.success, size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(done!, style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
              ],
            ),
          ),
        SoftCard(
          onTap: busy ? null : _changePin,
          padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
          child: Row(
            children: [
              const IconTile(Icons.lock_outline_rounded, size: 42),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Changer mon PIN', style: TextStyle(fontWeight: FontWeight.w600)),
                    Text('Avec votre PIN actuel', style: TextStyle(color: context.pal.muted, fontSize: 13)),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right_rounded),
            ],
          ),
        ),
        const SizedBox(height: 10),
        SoftCard(
          key: const ValueKey('daily-limit-entry'),
          onTap: busy
              ? null
              : () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => DailyLimitScreen(app: widget.app))),
          padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
          child: Row(
            children: [
              const IconTile(Icons.speed_rounded, size: 42),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Plafond d\'envoi par jour', style: TextStyle(fontWeight: FontWeight.w600)),
                    Text('Baissez-le : un voleur ne pourra pas tout prendre', style: TextStyle(color: context.pal.muted, fontSize: 13)),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right_rounded),
            ],
          ),
        ),
        const SizedBox(height: 24),
        const Text('Connexion par empreinte', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
        const SizedBox(height: 4),
        Text(
          widget.app.biometricsAvailable
              ? 'Ouvrez l\'app avec votre doigt au lieu du PIN. Le PIN reste demandé pour envoyer de l\'argent, '
                    'et marche toujours si le lecteur refuse.'
              : 'Aucune empreinte n\'est enregistrée sur ce téléphone. Ajoutez-en une dans ses réglages, '
                    'puis revenez ici.',
          style: TextStyle(color: context.pal.muted, fontSize: 13),
        ),
        if (widget.app.biometricsAvailable) ...[
          const SizedBox(height: 10),
          ListenableBuilder(
            listenable: widget.app,
            builder: (context, _) => SoftCard(
              padding: const EdgeInsets.fromLTRB(14, 6, 6, 6),
              child: SwitchListTile(
                key: const ValueKey('biometric-switch'),
                contentPadding: EdgeInsets.zero,
                secondary: const IconTile(Icons.fingerprint_rounded, size: 42),
                title: const Text('Se connecter avec l\'empreinte', style: TextStyle(fontWeight: FontWeight.w600)),
                subtitle: Text(widget.app.biometricEnabled ? 'Activée sur ce téléphone' : 'Désactivée'),
                value: widget.app.biometricEnabled,
                onChanged: busy ? null : _toggleBiometric,
              ),
            ),
          ),
          if (biometricNote != null)
            Padding(
              key: const ValueKey('biometric-note'),
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                biometricNote!,
                style: TextStyle(
                  color: biometricNoteIsError ? Brand.danger : context.pal.muted,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
        ],
        const SizedBox(height: 24),
        const Text('Verrouillage automatique', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
        const SizedBox(height: 4),
        Text(
          'Après ce temps hors de l\'app, votre PIN est redemandé. Utile si le téléphone est perdu ou volé déverrouillé.',
          style: TextStyle(color: context.pal.muted, fontSize: 13),
        ),
        const SizedBox(height: 10),
        ListenableBuilder(
          listenable: widget.app,
          builder: (context, _) => SegmentedButton<Duration>(
            key: const ValueKey('lock-after'),
            showSelectedIcon: false,
            segments: const [
              ButtonSegment(value: Duration.zero, label: Text('Tout de suite')),
              ButtonSegment(value: Duration(minutes: 1), label: Text('1 min')),
              ButtonSegment(value: Duration(minutes: 5), label: Text('5 min')),
            ],
            selected: {widget.app.lockAfter},
            onSelectionChanged: (s) => widget.app.setLockAfter(s.single),
          ),
        ),
        const SizedBox(height: 24),
        const Text('Connexions récentes', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
        const SizedBox(height: 4),
        Text(
          'Un téléphone que vous ne connaissez pas ? Changez votre PIN tout de suite et appelez le service client.',
          style: TextStyle(color: context.pal.muted, fontSize: 13),
        ),
        const SizedBox(height: 12),
        for (final s in l ?? const <DeviceSession>[]) _SessionTile(s),
        if (othersActive) ...[
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: busy ? null : _signOutOthers,
            icon: const Icon(Icons.phonelink_erase_rounded),
            label: const Text('Déconnecter les autres téléphones'),
          ),
        ],
        const SizedBox(height: 24),
        const Text('Téléphone perdu ou volé', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
        const SizedBox(height: 4),
        Text(
          'Bloquez votre compte : plus rien ne part. Vous pouvez aussi le faire depuis le téléphone d\'un proche, sur l\'écran de connexion.',
          style: TextStyle(color: context.pal.muted, fontSize: 13),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          key: const Key('block-account'),
          style: OutlinedButton.styleFrom(foregroundColor: Brand.danger, side: const BorderSide(color: Brand.danger)),
          onPressed: busy
              ? null
              : () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => BlockAccountScreen(app: widget.app, signedIn: true)),
                ),
          icon: const Icon(Icons.lock_rounded),
          label: const Text('Bloquer mon compte'),
        ),
        const SizedBox(height: 24),
        TextButton(
          key: const Key('close-account'),
          style: TextButton.styleFrom(foregroundColor: context.pal.muted),
          onPressed: busy
              ? null
              : () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CloseAccountScreen(app: widget.app))),
          child: const Text('Fermer mon compte'),
        ),
      ],
    );
  }
}

/// PIN redemandé pour activer l'empreinte : un téléphone laissé ouvert ne suffit pas.
class _PinDialog extends StatefulWidget {
  const _PinDialog();

  @override
  State<_PinDialog> createState() => _PinDialogState();
}

class _PinDialogState extends State<_PinDialog> {
  final pin = TextEditingController();
  String? error;

  void _submit() {
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    Navigator.of(context).pop(pin.text);
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Activer l\'empreinte'),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text('Entrez votre PIN, puis posez votre doigt sur le lecteur.'),
        const SizedBox(height: 12),
        ErrorBanner(error),
        PinField(controller: pin, autofocus: true, onSubmitted: (_) => _submit()),
      ],
    ),
    actions: [
      TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Annuler')),
      FilledButton(onPressed: _submit, child: const Text('Continuer')),
    ],
  );
}

class _SessionTile extends StatelessWidget {
  const _SessionTile(this.s);

  final DeviceSession s;

  @override
  Widget build(BuildContext context) {
    final status = s.current
        ? 'Ce téléphone'
        : s.active
        ? 'Encore connecté'
        : 'Déconnecté';
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: SoftCard(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
        child: Row(
          children: [
            IconTile(Icons.smartphone_rounded, size: 42, accent: s.current),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(s.deviceName ?? 'Téléphone sans nom', style: const TextStyle(fontWeight: FontWeight.w600)),
                  Text(
                    'Connexion à ${formatDateTime(s.createdAt)}\n$status',
                    style: TextStyle(color: context.pal.muted, fontSize: 13),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Nouveau PIN, avec l'actuel pour prouver que c'est bien le titulaire du compte.
class ChangePinScreen extends StatefulWidget {
  const ChangePinScreen({super.key, required this.app});

  final AppState app;

  @override
  State<ChangePinScreen> createState() => _ChangePinScreenState();
}

class _ChangePinScreenState extends State<ChangePinScreen> with ApiAction {
  final current = TextEditingController();
  final pin = TextEditingController();
  final pin2 = TextEditingController();

  Future<void> _save() async {
    if (current.text.length != 4) return setState(() => error = 'Entrez votre PIN actuel.');
    final problem = pinProblem(pin.text);
    if (problem != null) return setState(() => error = '$problem.');
    if (pin.text != pin2.text) return setState(() => error = 'Les deux nouveaux PIN ne sont pas identiques.');
    if (pin.text == current.text) return setState(() => error = 'Choisissez un PIN différent de l\'actuel.');
    final ok = await run(widget.app, () async {
      await widget.app.api.changePin(current.text, pin.text);
      return true;
    });
    current.clear();
    if (ok == true && mounted) {
      Navigator.of(context).pop(true);
    } else if (mounted && error == 'Numéro ou PIN incorrect.') {
      setState(() => error = 'PIN actuel incorrect.');
    }
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: 'Changer mon PIN',
    subtitle: 'Vos autres téléphones seront déconnectés. Vous recevrez un SMS de confirmation.',
    children: [
      ErrorBanner(error),
      PinField(controller: current, label: 'PIN actuel', autofocus: true),
      const SizedBox(height: 20),
      PinField(controller: pin, label: 'Nouveau PIN'),
      const SizedBox(height: 12),
      PinField(controller: pin2, label: 'Confirmez le nouveau PIN', onSubmitted: (_) => _save()),
      const SizedBox(height: 8),
      Text(
        'Ni 1111, ni 1234, ni votre date de naissance. Ne le donnez à personne, même à un agent.',
        style: TextStyle(color: context.pal.muted, fontSize: 13),
      ),
      const SizedBox(height: 20),
      BusyButton(label: 'Changer mon PIN', busy: busy, onPressed: _save),
    ],
  );
}
