import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Épargne : l'argent mis de côté, l'objectif, et les derniers mouvements.
class SavingsScreen extends StatefulWidget {
  const SavingsScreen({super.key, required this.app});

  final AppState app;

  @override
  State<SavingsScreen> createState() => _SavingsScreenState();
}

class _SavingsScreenState extends State<SavingsScreen> with ApiAction {
  Savings? savings;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = await run(widget.app, widget.app.api.savings);
    if (s != null && mounted) setState(() => savings = s);
  }

  Future<void> _open(Widget screen) async {
    final s = await Navigator.of(context).push<Savings>(MaterialPageRoute(builder: (_) => screen));
    if (s != null && mounted) setState(() => savings = s);
  }

  @override
  Widget build(BuildContext context) {
    final s = savings;
    return FormPage(
      title: 'Épargne',
      subtitle: 'Mettez de l\'argent de côté. Il reste à vous : vous le reprenez quand vous voulez, sans frais.',
      children: [
        ErrorBanner(error),
        if (s != null) ...[
          SoftCard(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const IconTile(Icons.savings_outlined, size: 42, accent: true),
                    const SizedBox(width: 12),
                    Text('Mis de côté', style: TextStyle(color: context.pal.muted)),
                  ],
                ),
                const SizedBox(height: 10),
                Amount(formatUsd(s.balanceMinor), key: const ValueKey('savings-balance')),
                const SizedBox(height: 4),
                Text(
                  s.savedThisMonthMinor == 0
                      ? 'Rien de nouveau ce mois-ci'
                      : 'Ce mois-ci : ${s.savedThisMonthMinor > 0 ? '+' : ''}${formatUsd(s.savedThisMonthMinor)}',
                  style: TextStyle(
                    color: s.savedThisMonthMinor > 0 ? Brand.success : context.pal.muted,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 18),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton(
                        key: const ValueKey('savings-deposit'),
                        onPressed: () => _open(SavingsMoveScreen(app: widget.app, savings: s, deposit: true)),
                        child: const Text('Mettre de côté', maxLines: 1),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: OutlinedButton(
                        key: const ValueKey('savings-withdraw'),
                        onPressed: s.balanceMinor == 0
                            ? null
                            : () => _open(SavingsMoveScreen(app: widget.app, savings: s, deposit: false)),
                        child: const Text('Reprendre', maxLines: 1),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          _GoalCard(savings: s, onEdit: () => _open(SavingsGoalScreen(app: widget.app, goal: s.goal))),
          const SizedBox(height: 20),
          const Text('Derniers mouvements', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          const SizedBox(height: 8),
          if (s.moves.isEmpty)
            Text(
              'Rien pour l\'instant. Commencez petit : même 1 \$ par semaine finit par compter.',
              style: TextStyle(color: context.pal.muted),
            ),
          for (final m in s.moves)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  IconTile(m.isDeposit ? Icons.south_rounded : Icons.north_rounded, size: 38),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(m.isDeposit ? 'Mis de côté' : 'Repris vers le solde',
                            style: const TextStyle(fontWeight: FontWeight.w600)),
                        Text(formatDateTime(m.createdAt), style: TextStyle(color: context.pal.muted, fontSize: 13)),
                      ],
                    ),
                  ),
                  Text(
                    '${m.isDeposit ? '+' : '-'}${formatUsd(m.amountMinor)}',
                    style: TextStyle(fontWeight: FontWeight.w700, color: m.isDeposit ? Brand.success : null),
                  ),
                ],
              ),
            ),
        ],
      ],
    );
  }
}

class _GoalCard extends StatelessWidget {
  const _GoalCard({required this.savings, required this.onEdit});

  final Savings savings;
  final VoidCallback onEdit;

  @override
  Widget build(BuildContext context) {
    final g = savings.goal;
    if (g == null) {
      return SoftCard(
        key: const ValueKey('savings-goal'),
        onTap: onEdit,
        padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
        child: Row(
          children: [
            const IconTile(Icons.flag_outlined, size: 42),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Fixer un objectif', style: TextStyle(fontWeight: FontWeight.w600)),
                  Text('Rentrée scolaire, loyer, moto… et voyez où vous en êtes.',
                      style: TextStyle(color: context.pal.muted, fontSize: 13)),
                ],
              ),
            ),
            const Icon(Icons.chevron_right_rounded),
          ],
        ),
      );
    }
    final reached = g.remainingMinor == 0;
    return SoftCard(
      key: const ValueKey('savings-goal'),
      onTap: onEdit,
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.flag_rounded, color: Brand.ochreDeep),
              const SizedBox(width: 8),
              Expanded(child: Text(g.label, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
              Text('${g.progressPercent} %', style: const TextStyle(fontWeight: FontWeight.w700)),
            ],
          ),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: LinearProgressIndicator(
              value: g.progressPercent / 100,
              minHeight: 10,
              backgroundColor: context.pal.surfaceAlt,
              color: reached ? Brand.success : Brand.ochre,
            ),
          ),
          const SizedBox(height: 10),
          Text(
            reached
                ? 'Objectif atteint : ${formatUsd(g.targetMinor)}. Bravo !'
                : 'Encore ${formatUsd(g.remainingMinor)} pour atteindre ${formatUsd(g.targetMinor)}',
            style: TextStyle(color: reached ? Brand.success : context.pal.muted, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

/// Mettre de côté (depuis le solde) ou reprendre (vers le solde).
class SavingsMoveScreen extends StatefulWidget {
  const SavingsMoveScreen({super.key, required this.app, required this.savings, required this.deposit});

  final AppState app;
  final Savings savings;
  final bool deposit;

  @override
  State<SavingsMoveScreen> createState() => _SavingsMoveScreenState();
}

class _SavingsMoveScreenState extends State<SavingsMoveScreen> with ApiAction {
  final amount = TextEditingController();

  /// Gardée pour toute la saisie : un nouvel essai après une coupure ne déplace pas l'argent deux fois.
  String _key = newIdempotencyKey();

  int get _available => widget.deposit ? widget.savings.walletBalanceMinor : widget.savings.balanceMinor;

  Future<void> _submit() async {
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 10 ou 2,50.');
    if (minor > _available) {
      return setState(
        () => error = widget.deposit
            ? 'Vous avez ${formatUsd(_available)} sur votre solde.'
            : 'Votre épargne contient ${formatUsd(_available)}.',
      );
    }
    final api = widget.app.api;
    final s = await run(widget.app, () => widget.deposit ? api.saveMoney(minor, _key) : api.takeBackSavings(minor, _key));
    if (s != null && mounted) Navigator.of(context).pop(s);
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: widget.deposit ? 'Mettre de côté' : 'Reprendre',
    subtitle: widget.deposit
        ? 'L\'argent passe de votre solde à votre épargne. Sans frais, sans PIN.'
        : 'L\'argent revient sur votre solde tout de suite, sans frais.',
    children: [
      ErrorBanner(error),
      Text(
        widget.deposit
            ? 'Disponible sur votre solde : ${formatUsd(_available)}'
            : 'Dans votre épargne : ${formatUsd(_available)}',
        style: TextStyle(color: context.pal.muted),
      ),
      const SizedBox(height: 12),
      TextField(
        key: const ValueKey('amount'),
        controller: amount,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        decoration: const InputDecoration(labelText: 'Montant', suffixText: '\$'),
        onChanged: (_) => _key = newIdempotencyKey(),
      ),
      const SizedBox(height: 10),
      Wrap(
        spacing: 8,
        children: [
          for (final (label, value) in [('5 \$', 500), ('10 \$', 1000), ('20 \$', 2000), ('Tout', _available)])
            if (value > 0 && value <= _available)
              ActionChip(
                label: Text(label),
                onPressed: () {
                  amount.text = (value / 100).toStringAsFixed(value % 100 == 0 ? 0 : 2).replaceAll('.', ',');
                  _key = newIdempotencyKey();
                },
              ),
        ],
      ),
      const SizedBox(height: 20),
      BusyButton(label: widget.deposit ? 'Mettre de côté' : 'Reprendre', busy: busy, onPressed: _submit),
    ],
  );
}

/// Nom et montant de l'objectif ; on peut aussi le retirer.
class SavingsGoalScreen extends StatefulWidget {
  const SavingsGoalScreen({super.key, required this.app, this.goal});

  final AppState app;
  final SavingsGoal? goal;

  @override
  State<SavingsGoalScreen> createState() => _SavingsGoalScreenState();
}

class _SavingsGoalScreenState extends State<SavingsGoalScreen> with ApiAction {
  late final label = TextEditingController(text: widget.goal?.label);
  late final target = TextEditingController(
    text: widget.goal == null ? null : (widget.goal!.targetMinor / 100).toStringAsFixed(0),
  );

  Future<void> _save() async {
    final minor = parseUsd(target.text);
    if (label.text.trim().isEmpty) return setState(() => error = 'Donnez un nom à votre objectif.');
    if (minor == null) return setState(() => error = 'Entrez le montant à atteindre, par exemple 300.');
    final s = await run(widget.app, () => widget.app.api.setSavingsGoal(label.text.trim(), minor));
    if (s != null && mounted) Navigator.of(context).pop(s);
  }

  Future<void> _remove() async {
    final s = await run(widget.app, widget.app.api.clearSavingsGoal);
    if (s != null && mounted) Navigator.of(context).pop(s);
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: widget.goal == null ? 'Fixer un objectif' : 'Modifier l\'objectif',
    subtitle: 'Un nom et un montant : l\'app vous montre combien il reste à mettre de côté.',
    children: [
      ErrorBanner(error),
      TextField(
        key: const ValueKey('goal-label'),
        controller: label,
        maxLength: 40,
        textCapitalization: TextCapitalization.sentences,
        decoration: const InputDecoration(labelText: 'Pour quoi ?', hintText: 'Rentrée scolaire'),
      ),
      const SizedBox(height: 8),
      TextField(
        key: const ValueKey('goal-target'),
        controller: target,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        decoration: const InputDecoration(labelText: 'Montant à atteindre', suffixText: '\$'),
      ),
      const SizedBox(height: 20),
      BusyButton(label: 'Enregistrer', busy: busy, onPressed: _save),
      if (widget.goal != null) ...[
        const SizedBox(height: 8),
        TextButton(onPressed: busy ? null : _remove, child: const Text('Retirer l\'objectif')),
      ],
    ],
  );
}
