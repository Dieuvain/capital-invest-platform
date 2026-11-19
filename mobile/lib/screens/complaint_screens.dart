import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'history_screens.dart';
import 'security_screen.dart';

const _categories = [
  ('NOT_RECEIVED', 'L\'argent n\'est pas arrivé'),
  ('WRONG_AMOUNT', 'Mauvais montant ou frais'),
  ('NOT_ME', 'Je n\'ai pas fait cette opération'),
  ('OTHER', 'Autre problème'),
];

/// Signaler un problème : sur une opération (depuis son reçu) ou sur le compte.
class NewComplaintScreen extends StatefulWidget {
  const NewComplaintScreen({super.key, required this.app, this.operation});

  final AppState app;
  final HistoryItem? operation;

  @override
  State<NewComplaintScreen> createState() => _NewComplaintScreenState();
}

class _NewComplaintScreenState extends State<NewComplaintScreen> with ApiAction {
  late String? category = widget.operation == null ? 'OTHER' : null;
  final message = TextEditingController();
  Complaint? sent;

  Future<void> _send() async {
    if (category == null) return setState(() => error = 'Choisissez ce qui ne va pas.');
    if (message.text.trim().isEmpty) return setState(() => error = 'Expliquez en quelques mots ce qui s\'est passé.');
    final c = await run(
      widget.app,
      () => widget.app.api.openComplaint(
        transactionId: widget.operation?.id,
        category: category!,
        message: message.text.trim(),
      ),
    );
    if (c != null && mounted) setState(() => sent = c);
  }

  @override
  Widget build(BuildContext context) {
    final c = sent;
    if (c != null) return _sent(c);
    final op = widget.operation;
    return FormPage(
      title: 'Signaler un problème',
      subtitle: op == null
          ? 'Le service client vous répond dans l\'app et par SMS.'
          : '${historyTitle(op)} · ${formatUsd(op.amountMinor)} · reçu ${op.receiptNumber}',
      children: [
        ErrorBanner(error),
        if (op != null) ...[
          const Text('Que s\'est-il passé ?', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          const SizedBox(height: 8),
          for (final (code, label) in _categories)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: SoftCard(
                key: ValueKey('complaint-category-$code'),
                onTap: () => setState(() => category = code),
                padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                child: Row(
                  children: [
                    Icon(
                      category == code ? Icons.radio_button_checked : Icons.radio_button_off,
                      color: category == code ? Brand.ochreDeep : context.pal.muted,
                    ),
                    const SizedBox(width: 12),
                    Expanded(child: Text(label, style: const TextStyle(fontWeight: FontWeight.w600))),
                  ],
                ),
              ),
            ),
          if (category == 'NOT_ME') ...[
            const SizedBox(height: 4),
            SoftCard(
              padding: const EdgeInsets.all(14),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.warning_amber_rounded, color: Brand.danger),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Quelqu\'un connaît peut-être votre PIN. Changez-le tout de suite : vos autres sessions seront fermées.',
                        ),
                        TextButton(
                          onPressed: () => Navigator.of(
                            context,
                          ).push(MaterialPageRoute<void>(builder: (_) => SecurityScreen(app: widget.app))),
                          child: const Text('Changer mon PIN'),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 12),
        ],
        TextField(
          key: const ValueKey('complaint-message'),
          controller: message,
          minLines: 3,
          maxLines: 6,
          maxLength: 1000,
          decoration: const InputDecoration(
            labelText: 'Expliquez le problème',
            hintText: 'Ex. : mon frère dit ne rien avoir reçu sur son compte.',
          ),
        ),
        const SizedBox(height: 12),
        BusyButton(label: 'Envoyer la réclamation', busy: busy, onPressed: _send),
      ],
    );
  }

  Widget _sent(Complaint c) => FormPage(
    title: 'Réclamation envoyée',
    children: [
      const SizedBox(height: 24),
      const Icon(Icons.mark_email_read_outlined, size: 72, color: Brand.success),
      const SizedBox(height: 16),
      Text(
        c.reference,
        key: const ValueKey('complaint-reference'),
        textAlign: TextAlign.center,
        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: 1),
      ),
      const SizedBox(height: 8),
      Text(
        'Gardez ce numéro. Le service client vous répond au plus tard le ${formatDay(c.dueAt)}, dans « Mes réclamations » et par SMS.',
        textAlign: TextAlign.center,
        style: TextStyle(color: context.pal.muted, fontSize: 15),
      ),
      const SizedBox(height: 24),
      FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
    ],
  );
}

/// Mes réclamations : en attente (avec la date de réponse promise) et répondues.
class ComplaintsScreen extends StatefulWidget {
  const ComplaintsScreen({super.key, required this.app});

  final AppState app;

  @override
  State<ComplaintsScreen> createState() => _ComplaintsScreenState();
}

class _ComplaintsScreenState extends State<ComplaintsScreen> with ApiAction {
  List<Complaint>? items;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, widget.app.api.complaints);
    if (l != null && mounted) setState(() => items = l);
  }

  Future<void> _new() async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => NewComplaintScreen(app: widget.app)));
    if (mounted) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final list = items;
    return FormPage(
      title: 'Mes réclamations',
      subtitle: 'Pour une opération, ouvrez son reçu puis « Signaler un problème ».',
      children: [
        ErrorBanner(error),
        if (list == null && error == null) const Center(child: CircularProgressIndicator()),
        if (list != null && list.isEmpty)
          SoftCard(
            padding: const EdgeInsets.all(20),
            child: Text('Aucune réclamation.', style: TextStyle(color: context.pal.muted)),
          ),
        for (final c in list ?? const <Complaint>[])
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: SoftCard(
              key: ValueKey('complaint-${c.id}'),
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(c.reference, style: const TextStyle(fontWeight: FontWeight.w700)),
                      ),
                      _Badge(c),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    [
                      c.categoryLabel,
                      if (c.operation case final op?) '${historyTitle(op)} · ${formatUsd(op.amountMinor)}',
                    ].join(' · '),
                    style: TextStyle(color: context.pal.muted, fontSize: 13),
                  ),
                  const SizedBox(height: 8),
                  Text('« ${c.message} »'),
                  if (c.answer case final answer?) ...[
                    const SizedBox(height: 12),
                    const Divider(height: 1),
                    const SizedBox(height: 12),
                    Text(
                      'Réponse du service client · ${formatDay(c.answeredAt!)}',
                      style: TextStyle(color: context.pal.muted, fontSize: 13),
                    ),
                    const SizedBox(height: 4),
                    Text(answer, key: ValueKey('complaint-answer-${c.id}'), style: const TextStyle(fontWeight: FontWeight.w600)),
                  ] else ...[
                    const SizedBox(height: 8),
                    Text(
                      'Réponse au plus tard le ${formatDay(c.dueAt)}.',
                      style: TextStyle(color: context.pal.muted, fontSize: 13),
                    ),
                  ],
                  if (c.operation case final op?)
                    Align(
                      alignment: Alignment.centerLeft,
                      child: TextButton(
                        onPressed: () => Navigator.of(
                          context,
                        ).push(MaterialPageRoute<void>(builder: (_) => ReceiptScreen(app: widget.app, id: op.id))),
                        child: const Text('Voir le reçu'),
                      ),
                    ),
                ],
              ),
            ),
          ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: _new,
          icon: const Icon(Icons.edit_outlined),
          label: const Text('Signaler un autre problème'),
        ),
      ],
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge(this.c);

  final Complaint c;

  @override
  Widget build(BuildContext context) {
    final (label, color) = c.open
        ? ('En attente', Brand.ochreDeep)
        : c.outcome == 'FOUNDED'
        ? ('Réglée en votre faveur', Brand.success)
        : ('Répondue', context.pal.muted);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(20)),
      child: Text(label, style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w700)),
    );
  }
}
