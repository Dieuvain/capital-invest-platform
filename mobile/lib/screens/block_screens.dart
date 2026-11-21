import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Téléphone perdu ou volé : bloquer son compte, depuis l'app ou depuis le téléphone de quelqu'un d'autre.
/// Sans session ([signedIn] faux), le client donne son numéro en plus de son PIN.
class BlockAccountScreen extends StatefulWidget {
  const BlockAccountScreen({super.key, required this.app, this.signedIn = false});

  final AppState app;
  final bool signedIn;

  @override
  State<BlockAccountScreen> createState() => _BlockAccountScreenState();
}

class _BlockAccountScreenState extends State<BlockAccountScreen> with ApiAction {
  final phone = TextEditingController();
  final pin = TextEditingController();

  Future<void> _block() async {
    final p = widget.signedIn ? null : normalizePhone(phone.text);
    if (!widget.signedIn && p == null) {
      return setState(() => error = 'Numéro congolais attendu (9 chiffres après +243).');
    }
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final ok = await run(widget.app, () async {
      if (widget.signedIn) {
        await widget.app.api.blockAccount(pin.text);
      } else {
        await widget.app.api.blockAccountFromPhone(p!, pin.text);
      }
      return true;
    });
    pin.clear();
    if (ok != true || !mounted) return;
    final app = widget.app;
    // Le Future de pushReplacement ne se termine qu'à la fermeture de l'écran : on ne l'attend pas.
    unawaited(Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const AccountBlockedScreen())));
    // Toutes les sessions sont fermées côté serveur, celle-ci aussi, et l'empreinte retirée partout.
    if ((widget.signedIn ? app.customer?.phone : p) == app.biometricPhone) await app.forgetBiometric();
    if (widget.signedIn) await app.signOut(callServer: false);
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: 'Bloquer mon compte',
    subtitle: 'Téléphone perdu ou volé ? Bloquez votre compte tout de suite, même depuis le téléphone d\'un proche.',
    children: [
      ErrorBanner(error),
      const _Point(Icons.phonelink_lock_rounded, 'Tous les téléphones sont déconnectés et plus personne ne peut entrer.'),
      const _Point(Icons.money_off_rounded, 'Aucun envoi, retrait ni paiement ne peut partir. Votre argent reste sur le compte.'),
      const _Point(
        Icons.badge_outlined,
        'Pour débloquer, présentez-vous en agence avec votre pièce d\'identité. Le service client ne peut pas le faire par téléphone.',
      ),
      const SizedBox(height: 16),
      if (!widget.signedIn) ...[
        PhoneField(controller: phone, label: 'Numéro du compte à bloquer'),
        const SizedBox(height: 12),
      ],
      PinField(controller: pin, autofocus: widget.signedIn, onSubmitted: (_) => _block()),
      const SizedBox(height: 20),
      FilledButton.icon(
        key: const Key('block-confirm'),
        style: FilledButton.styleFrom(backgroundColor: Brand.danger, foregroundColor: Colors.white),
        onPressed: busy ? null : _block,
        icon: busy
            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
            : const Icon(Icons.lock_rounded),
        label: const Text('Bloquer mon compte'),
      ),
      const SizedBox(height: 8),
      Text(
        'Vous avez oublié votre PIN ? Appelez le service client : un agent peut aussi bloquer le compte.',
        style: TextStyle(color: context.pal.muted, fontSize: 13),
      ),
    ],
  );
}

class _Point extends StatelessWidget {
  const _Point(this.icon, this.text);

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 20, color: context.pal.muted),
        const SizedBox(width: 10),
        Expanded(child: Text(text)),
      ],
    ),
  );
}

/// Confirmation : le compte est bloqué, et voici comment le débloquer.
class AccountBlockedScreen extends StatelessWidget {
  const AccountBlockedScreen({super.key});

  @override
  Widget build(BuildContext context) => PopScope(
    // Les écrans d'avant appartiennent à une session fermée : le retour mène à l'écran de connexion.
    canPop: false,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop) Navigator.of(context).popUntil((r) => r.isFirst);
    },
    child: _content(context),
  );

  Widget _content(BuildContext context) => FormPage(
    title: 'Compte bloqué',
    children: [
      const Row(
        children: [
          Icon(Icons.lock_rounded, color: Brand.success, size: 28),
          SizedBox(width: 10),
          Expanded(
            child: Text(
              'Plus personne ne peut se connecter ni faire partir votre argent.',
              style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16),
            ),
          ),
        ],
      ),
      const SizedBox(height: 16),
      const Text('Un SMS de confirmation part sur votre numéro.'),
      const SizedBox(height: 8),
      const Text(
        'Pour débloquer : présentez-vous dans une agence Capital Invest avec votre pièce d\'identité. '
        'Si votre carte SIM a été volée, demandez d\'abord un duplicata à votre opérateur, puis changez votre PIN après le déblocage.',
      ),
      const SizedBox(height: 24),
      FilledButton(
        onPressed: () => Navigator.of(context).popUntil((r) => r.isFirst),
        child: const Text('Compris'),
      ),
    ],
  );
}
