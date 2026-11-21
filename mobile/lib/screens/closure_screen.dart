import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

const _reasons = [
  ('NOT_USED', 'Je ne m\'en sers plus'),
  ('FEES', 'Les frais sont trop élevés'),
  ('OTHER_SERVICE', 'Je passe à un autre service'),
  ('MOVING', 'Je quitte la région'),
  ('OTHER', 'Autre raison'),
];

/// Fermer son compte : d'abord ce qu'il reste à faire (solde, épargne, opérations en cours), puis le motif et le PIN.
class CloseAccountScreen extends StatefulWidget {
  const CloseAccountScreen({super.key, required this.app});

  final AppState app;

  @override
  State<CloseAccountScreen> createState() => _CloseAccountScreenState();
}

class _CloseAccountScreenState extends State<CloseAccountScreen> with ApiAction {
  final pin = TextEditingController();
  final comment = TextEditingController();
  ClosureCheck? check;
  String? reason;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final c = await run(widget.app, widget.app.api.closureCheck);
    if (c != null && mounted) setState(() => check = c);
  }

  Future<void> _close() async {
    if (reason == null) return setState(() => error = 'Dites-nous pourquoi vous partez.');
    if (pin.text.length != 4) return setState(() => error = 'Entrez votre PIN pour confirmer.');
    final text = comment.text.trim();
    final ok = await run(widget.app, () async {
      await widget.app.api.closeAccount(pin.text, reason!, text.isEmpty ? null : text);
      return true;
    });
    pin.clear();
    if (ok != true || !mounted) return;
    final app = widget.app;
    unawaited(Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const AccountClosedScreen())));
    if (app.biometricEnabled) await app.forgetBiometric();
    await app.signOut(callServer: false);
  }

  @override
  Widget build(BuildContext context) {
    final c = check;
    return FormPage(
      title: 'Fermer mon compte',
      subtitle: 'Vos opérations restent conservées. Vous pourrez rouvrir un compte en agence, avec votre pièce d\'identité.',
      children: [
        ErrorBanner(error),
        if (c != null && !c.canClose) ...[
          const Text('Avant de fermer', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          for (final (code, message) in c.blockers)
            Padding(
              key: ValueKey('blocker-$code'),
              padding: const EdgeInsets.only(bottom: 10),
              child: SoftCard(
                padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      switch (code) {
                        'SAVINGS_NOT_EMPTY' => Icons.savings_outlined,
                        'BALANCE_NOT_EMPTY' => Icons.account_balance_wallet_outlined,
                        _ => Icons.hourglass_top_rounded,
                      },
                      color: context.pal.muted,
                    ),
                    const SizedBox(width: 12),
                    Expanded(child: Text(message)),
                  ],
                ),
              ),
            ),
          Text(
            'Revenez ici quand c\'est fait. Un compte fermé ne peut plus rien recevoir ni rendre.',
            style: TextStyle(color: context.pal.muted, fontSize: 13),
          ),
        ],
        if (c != null && c.canClose) ...[
          const Text('Pourquoi partez-vous ?', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final (code, label) in _reasons)
                ChoiceChip(
                  key: ValueKey('reason-$code'),
                  label: Text(label),
                  selected: reason == code,
                  onSelected: (_) => setState(() => reason = code),
                ),
            ],
          ),
          const SizedBox(height: 12),
          TextField(
            controller: comment,
            maxLength: 300,
            maxLines: 2,
            decoration: const InputDecoration(labelText: 'Un mot pour nous (facultatif)'),
          ),
          const SizedBox(height: 8),
          PinField(controller: pin, label: 'PIN pour confirmer', onSubmitted: (_) => _close()),
          const SizedBox(height: 20),
          FilledButton(
            key: const Key('close-confirm'),
            style: FilledButton.styleFrom(backgroundColor: Brand.danger, foregroundColor: Colors.white),
            onPressed: busy ? null : _close,
            child: const Text('Fermer définitivement mon compte'),
          ),
          const SizedBox(height: 8),
          Text(
            'Vos envois programmés seront arrêtés et ce téléphone déconnecté.',
            style: TextStyle(color: context.pal.muted, fontSize: 13),
          ),
        ],
      ],
    );
  }
}

class AccountClosedScreen extends StatelessWidget {
  const AccountClosedScreen({super.key});

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: false,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop) Navigator.of(context).popUntil((r) => r.isFirst);
    },
    child: FormPage(
      title: 'Compte fermé',
      children: [
        const Text(
          'Votre compte est fermé. Merci de nous avoir fait confiance.',
          style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16),
        ),
        const SizedBox(height: 12),
        const Text('Un SMS de confirmation part sur votre numéro. Pour rouvrir un compte, présentez-vous en agence avec votre pièce d\'identité.'),
        const SizedBox(height: 24),
        FilledButton(
          onPressed: () => Navigator.of(context).popUntil((r) => r.isFirst),
          child: const Text('Terminer'),
        ),
      ],
    ),
  );
}
