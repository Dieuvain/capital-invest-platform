import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/credit_models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'card_screens.dart' show askPin;

/// Libellé et couleur de l'état d'un crédit, du point de vue du client.
(String, Color) creditStatusLabel(BuildContext context, String status) => switch (status) {
  'PENDING_REVIEW' => ('En examen', Brand.ochreDeep),
  'ACTIVE' => ('À jour', Brand.success),
  'LATE' => ('En retard', Brand.danger),
  'DEFAULTED' => ('En défaut', Brand.danger),
  'REPAID' => ('Remboursé', Brand.success),
  'REJECTED' => ('Non accordé', context.pal.muted),
  'WRITTEN_OFF' => ('Non remboursé', Brand.danger),
  _ => (status, context.pal.muted),
};

class _StatusPill extends StatelessWidget {
  const _StatusPill(this.status);

  final String status;

  @override
  Widget build(BuildContext context) {
    final (label, color) = creditStatusLabel(context, status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(999)),
      child: Text(label, style: TextStyle(color: color, fontWeight: FontWeight.w700, fontSize: 12.5)),
    );
  }
}

class _Factors extends StatelessWidget {
  const _Factors(this.factors);

  final List<CreditFactor> factors;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      for (final f in factors)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                f.positive ? Icons.check_circle_rounded : Icons.remove_circle_outline_rounded,
                size: 20,
                color: f.positive ? Brand.success : context.pal.muted,
              ),
              const SizedBox(width: 10),
              Expanded(child: Text(f.label, style: const TextStyle(height: 1.3))),
            ],
          ),
        ),
    ],
  );
}

class _Levers extends StatelessWidget {
  const _Levers(this.levers);

  final List<String> levers;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Text('Pour progresser', style: TextStyle(fontWeight: FontWeight.w700)),
      const SizedBox(height: 6),
      for (final l in levers)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.trending_up_rounded, size: 20, color: Brand.ochreDeep),
              const SizedBox(width: 10),
              Expanded(child: Text(l, style: TextStyle(color: context.pal.muted, height: 1.3))),
            ],
          ),
        ),
    ],
  );
}

/// Score sur 100, en barre.
class _ScoreBar extends StatelessWidget {
  const _ScoreBar(this.score);

  final int score;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Text('Votre score', style: TextStyle(color: context.pal.muted)),
          const Spacer(),
          Text('$score sur 100', key: const ValueKey('credit-score'), style: const TextStyle(fontWeight: FontWeight.w700)),
        ],
      ),
      const SizedBox(height: 8),
      ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: LinearProgressIndicator(
          value: score / 100,
          minHeight: 8,
          backgroundColor: context.pal.surfaceAlt,
          color: Brand.ochre,
        ),
      ),
    ],
  );
}

/// Crédit express : l'offre et ses raisons, ou le crédit en cours avec ses échéances.
class CreditScreen extends StatefulWidget {
  const CreditScreen({super.key, required this.app, this.onKyc});

  final AppState app;
  final VoidCallback? onKyc;

  @override
  State<CreditScreen> createState() => _CreditScreenState();
}

class _CreditScreenState extends State<CreditScreen> with ApiAction {
  CreditOverview? overview;
  String? notice;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final o = await run(widget.app, widget.app.api.creditOverview);
    if (o != null && mounted) setState(() => overview = o);
  }

  Future<void> _apply(CreditOffer offer) async {
    final c = await Navigator.of(context).push<Credit>(
      MaterialPageRoute(builder: (_) => CreditApplyScreen(app: widget.app, offer: offer)),
    );
    if (c == null || !mounted) return;
    setState(
      () => notice = c.pendingReview
          ? 'Demande envoyée. Un agent de crédit l\'examine, vous recevrez un SMS.'
          : '${formatUsd(c.principalMinor)} sont sur votre compte et votre carte.',
    );
    await _load();
  }

  Future<void> _repay(Credit c, {required bool full}) async {
    final amount = full ? c.payoffMinor : (c.dueNowMinor > 0 ? c.dueNowMinor : c.nextInstalment?.remainingMinor ?? 0);
    final pin = await askPin(
      context,
      title: full ? 'Tout rembourser' : 'Rembourser',
      message: full
          ? '${formatUsd(amount)} seront pris sur votre solde. Les intérêts des mois à venir ne sont pas dus.'
          : c.dueNowMinor > 0
          ? 'Jusqu\'à ${formatUsd(amount)} seront pris sur votre solde.'
          : '${formatUsd(amount)} seront pris sur votre solde pour payer l\'échéance en avance.',
      confirm: 'Rembourser',
    );
    if (pin == null || !mounted) return;
    final updated = await run(
      widget.app,
      () => widget.app.api.repayCredit(c.id, full: full, pin: pin, idempotencyKey: newIdempotencyKey()),
    );
    if (updated == null || !mounted) return;
    setState(() => notice = updated.status == 'REPAID' ? 'Crédit remboursé. Merci !' : 'Remboursement reçu.');
    await _load();
  }

  Future<void> _cancel(Credit c) async {
    final updated = await run(widget.app, () => widget.app.api.cancelCredit(c.id));
    if (updated == null || !mounted) return;
    setState(() => notice = 'Demande retirée.');
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final o = overview;
    return FormPage(
      title: 'Crédit express',
      subtitle: 'Un petit crédit versé sur votre compte et votre carte, remboursé chaque mois depuis votre solde.',
      children: [
        ErrorBanner(error),
        if (notice != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text(
              notice!,
              key: const ValueKey('credit-notice'),
              style: const TextStyle(color: Brand.success, fontWeight: FontWeight.w600),
            ),
          ),
        if (o == null && error == null) const Center(child: CircularProgressIndicator()),
        if (o?.current case final c?) _current(c),
        if (o?.offer case final offer?) _offer(offer),
        if (o?.refusal case final r?) _refusal(r),
        if (o != null && o.past.isNotEmpty) ...[
          const SizedBox(height: 22),
          const Text('Crédits précédents', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          const SizedBox(height: 6),
          for (final p in o.past)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  const IconTile(Icons.payments_outlined, size: 38),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${formatUsd(p.principalMinor)} sur ${p.durationMonths} mois',
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                        Text(formatDay(p.createdAt), style: TextStyle(color: context.pal.muted, fontSize: 13)),
                        if (p.status == 'REJECTED' && p.decisionNote != null)
                          Text(p.decisionNote!, style: TextStyle(color: context.pal.muted, fontSize: 13)),
                      ],
                    ),
                  ),
                  _StatusPill(p.status),
                ],
              ),
            ),
        ],
      ],
    );
  }

  Widget _offer(CreditOffer offer) => SoftCard(
    key: const ValueKey('credit-offer'),
    padding: const EdgeInsets.all(20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Vous pouvez emprunter jusqu\'à', style: TextStyle(color: context.pal.muted)),
        const SizedBox(height: 6),
        Amount(formatUsd(offer.ceilingMinor), key: const ValueKey('credit-ceiling')),
        const SizedBox(height: 4),
        Text(
          'Sur ${offer.durationsMonths.join(', ')} mois · ${formatRate(offer.monthlyRateBp)} par mois (TEG ${formatRate(offer.aprBp)})',
          style: TextStyle(color: context.pal.muted, fontSize: 13),
        ),
        const SizedBox(height: 18),
        _ScoreBar(offer.score),
        const SizedBox(height: 12),
        _Factors(offer.factors),
        if (offer.levers.isNotEmpty) ...[const SizedBox(height: 12), _Levers(offer.levers)],
        const SizedBox(height: 18),
        FilledButton(
          key: const ValueKey('credit-choose'),
          onPressed: () => _apply(offer),
          child: const Text('Choisir mon crédit'),
        ),
      ],
    ),
  );

  Widget _refusal(CreditRefusal r) => SoftCard(
    key: const ValueKey('credit-refusal'),
    padding: const EdgeInsets.all(20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            IconTile(r.needsKyc ? Icons.verified_user_outlined : Icons.hourglass_empty_rounded, accent: true),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                r.needsKyc ? 'Le crédit express demande le niveau 2' : 'Pas encore de crédit pour vous',
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Text(r.message, style: TextStyle(color: context.pal.muted, height: 1.4)),
        if (r.score != null) ...[const SizedBox(height: 16), _ScoreBar(r.score!)],
        if (r.factors.isNotEmpty) ...[const SizedBox(height: 12), _Factors(r.factors)],
        if (r.levers.isNotEmpty) ...[const SizedBox(height: 12), _Levers(r.levers)],
        if (r.needsKyc && widget.onKyc != null) ...[
          const SizedBox(height: 16),
          FilledButton(
            onPressed: () {
              Navigator.of(context).pop();
              widget.onKyc!();
            },
            child: const Text('Passer au niveau 2'),
          ),
        ],
      ],
    ),
  );

  Widget _current(Credit c) {
    final next = c.nextInstalment;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SoftCard(
          key: const ValueKey('credit-current'),
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Text(
                    c.pendingReview ? 'Demande de crédit' : 'Reste à rembourser',
                    style: TextStyle(color: context.pal.muted),
                  ),
                  const Spacer(),
                  _StatusPill(c.status),
                ],
              ),
              const SizedBox(height: 6),
              Amount(formatUsd(c.pendingReview ? c.principalMinor : c.outstandingPrincipalMinor)),
              const SizedBox(height: 4),
              Text(
                '${formatUsd(c.principalMinor)} sur ${c.durationMonths} mois · ${formatRate(c.monthlyRateBp)} par mois (TEG ${formatRate(c.aprBp)})',
                style: TextStyle(color: context.pal.muted, fontSize: 13),
              ),
              if (c.pendingReview) ...[
                const SizedBox(height: 14),
                Text(
                  'Un agent de crédit examine votre demande. Vous recevez un SMS dès sa décision ; l\'argent arrive alors sur votre compte.',
                  style: TextStyle(color: context.pal.muted, height: 1.4),
                ),
                const SizedBox(height: 14),
                OutlinedButton(
                  key: const ValueKey('credit-cancel'),
                  onPressed: busy ? null : () => _cancel(c),
                  child: const Text('Retirer ma demande'),
                ),
              ],
              if (c.late) ...[
                const SizedBox(height: 14),
                Container(
                  key: const ValueKey('credit-late'),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Brand.danger.withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Text(
                    '${formatUsd(c.dueNowMinor)} en retard depuis ${c.daysLate} jour${c.daysLate > 1 ? 's' : ''}. '
                    'Rechargez votre compte : le montant est prélevé automatiquement. '
                    '${c.status == 'DEFAULTED' ? 'Passez à l\'agence pour trouver une solution.' : ''}',
                    style: const TextStyle(color: Brand.danger, height: 1.35, fontWeight: FontWeight.w600),
                  ),
                ),
              ] else if (next != null && c.running) ...[
                const SizedBox(height: 14),
                Row(
                  children: [
                    const IconTile(Icons.event_outlined, size: 38),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Prochaine échéance : ${formatUsd(next.remainingMinor)} le ${formatDay(next.dueDate)}',
                        key: const ValueKey('credit-next'),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  'Prélevée sur votre solde ce jour-là. Gardez ce montant sur votre compte.',
                  style: TextStyle(color: context.pal.muted, fontSize: 13),
                ),
              ],
              if (c.running) ...[
                const SizedBox(height: 16),
                FilledButton(
                  key: const ValueKey('credit-repay'),
                  onPressed: busy ? null : () => _repay(c, full: false),
                  child: Text(c.dueNowMinor > 0 ? 'Payer le retard' : 'Payer l\'échéance en avance'),
                ),
                const SizedBox(height: 10),
                OutlinedButton(
                  key: const ValueKey('credit-payoff'),
                  onPressed: busy ? null : () => _repay(c, full: true),
                  child: Text('Tout rembourser (${formatUsd(c.payoffMinor)})'),
                ),
              ],
            ],
          ),
        ),
        if (c.instalments.isNotEmpty) ...[
          const SizedBox(height: 20),
          const Text('Échéancier', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          const SizedBox(height: 6),
          for (final i in c.instalments) _InstalmentRow(i),
        ],
      ],
    );
  }
}

class _InstalmentRow extends StatelessWidget {
  const _InstalmentRow(this.i);

  final CreditInstalment i;

  @override
  Widget build(BuildContext context) {
    final cancelled = i.status == 'CANCELLED';
    final (label, color) = switch (i.status) {
      'PAID' => ('Payée', Brand.success),
      'LATE' => (i.paidMinor > 0 ? 'Reste ${formatUsd(i.remainingMinor)}' : 'En retard', Brand.danger),
      'CANCELLED' => ('Remplacée', context.pal.muted),
      _ => ('À venir', context.pal.muted),
    };
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        children: [
          SizedBox(
            width: 28,
            child: Text('${i.number}', style: TextStyle(color: context.pal.muted, fontWeight: FontWeight.w600)),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(formatDay(i.dueDate), style: const TextStyle(fontWeight: FontWeight.w600)),
                Text(label, style: TextStyle(color: color, fontSize: 13)),
              ],
            ),
          ),
          Text(
            formatUsd(cancelled ? i.paidMinor : i.amountMinor),
            style: TextStyle(fontWeight: FontWeight.w700, color: cancelled ? context.pal.muted : null),
          ),
        ],
      ),
    );
  }
}

/// Choisir le montant et la durée, voir les conditions, accepter avec le PIN.
class CreditApplyScreen extends StatefulWidget {
  const CreditApplyScreen({super.key, required this.app, required this.offer});

  final AppState app;
  final CreditOffer offer;

  @override
  State<CreditApplyScreen> createState() => _CreditApplyScreenState();
}

class _CreditApplyScreenState extends State<CreditApplyScreen> with ApiAction {
  final amount = TextEditingController();
  late int months = widget.offer.durationsMonths.contains(3) ? 3 : widget.offer.durationsMonths.first;
  CreditQuote? quote;

  /// Gardée tant que le choix ne change pas : un nouvel essai après une coupure ne verse pas deux fois.
  String _key = newIdempotencyKey();

  void _changed() {
    _key = newIdempotencyKey();
    if (quote != null) setState(() => quote = null);
  }

  Future<void> _quote() async {
    final o = widget.offer;
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 100.');
    if (minor < o.minAmountMinor || minor > o.ceilingMinor) {
      return setState(() => error = 'Choisissez un montant entre ${formatUsd(o.minAmountMinor)} et ${formatUsd(o.ceilingMinor)}.');
    }
    final q = await run(widget.app, () => widget.app.api.creditQuote(minor, months));
    if (q != null && mounted) setState(() => quote = q);
  }

  Future<void> _accept() async {
    final q = quote!;
    final pin = await askPin(
      context,
      title: 'Accepter le crédit',
      message: 'Vous recevez ${formatUsd(q.amountMinor)} et remboursez ${formatUsd(q.totalDueMinor)} en ${q.durationMonths} mois.',
      confirm: 'Accepter',
    );
    if (pin == null || !mounted) return;
    final c = await run(
      widget.app,
      () => widget.app.api.applyForCredit(amountMinor: q.amountMinor, durationMonths: q.durationMonths, pin: pin, idempotencyKey: _key),
    );
    if (c != null && mounted) Navigator.of(context).pop(c);
  }

  @override
  Widget build(BuildContext context) {
    final o = widget.offer;
    final q = quote;
    return FormPage(
      title: 'Mon crédit',
      subtitle: 'Jusqu\'à ${formatUsd(o.ceilingMinor)}. Les échéances sont prélevées chaque mois sur votre solde.',
      children: [
        ErrorBanner(error),
        TextField(
          key: const ValueKey('credit-amount'),
          controller: amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Montant', suffixText: '\$'),
          onChanged: (_) => _changed(),
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          children: [
            for (final v in {o.ceilingMinor ~/ 2 ~/ 1000 * 1000, o.ceilingMinor})
              if (v >= o.minAmountMinor)
                ActionChip(
                  label: Text(formatUsd(v)),
                  onPressed: () {
                    amount.text = formatAmountInput(v);
                    _changed();
                  },
                ),
          ],
        ),
        const SizedBox(height: 18),
        const Text('Durée', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          children: [
            for (final d in o.durationsMonths)
              ChoiceChip(
                key: ValueKey('credit-months-$d'),
                label: Text('$d mois'),
                selected: months == d,
                onSelected: (_) {
                  setState(() => months = d);
                  _changed();
                },
              ),
          ],
        ),
        const SizedBox(height: 20),
        if (q == null)
          BusyButton(key: const ValueKey('credit-quote'), label: 'Voir les conditions', busy: busy, onPressed: _quote)
        else ...[
          SoftCard(
            key: const ValueKey('credit-terms'),
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _line(context, 'Vous recevez', formatUsd(q.amountMinor)),
                _line(context, '${q.durationMonths} échéance${q.durationMonths > 1 ? 's' : ''} de', formatUsd(q.instalmentMinor)),
                _line(context, 'Intérêts', formatUsd(q.totalInterestMinor)),
                _line(context, 'Total à rembourser', formatUsd(q.totalDueMinor), bold: true),
                _line(context, 'Taux', '${formatRate(q.monthlyRateBp)} par mois'),
                _line(context, 'TEG', '${formatRate(q.aprBp)} par an'),
                const SizedBox(height: 10),
                Text(
                  'Première échéance le ${formatDay(q.schedule.first.dueDate)}. Pas de frais de dossier. '
                  'Vous pouvez tout rembourser plus tôt : les intérêts des mois restants ne sont alors pas dus.',
                  style: TextStyle(color: context.pal.muted, fontSize: 13, height: 1.35),
                ),
                if (q.needsReview) ...[
                  const SizedBox(height: 10),
                  Text(
                    'Au-dessus de ${formatUsd(o.reviewThresholdMinor)}, un agent de crédit examine la demande avant le versement.',
                    key: const ValueKey('credit-review-note'),
                    style: const TextStyle(color: Brand.ochreDeep, fontWeight: FontWeight.w600, fontSize: 13),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),
          BusyButton(
            key: const ValueKey('credit-accept'),
            label: q.needsReview ? 'Envoyer ma demande' : 'Accepter et recevoir l\'argent',
            busy: busy,
            onPressed: _accept,
          ),
        ],
      ],
    );
  }

  Widget _line(BuildContext context, String label, String value, {bool bold = false}) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Row(
      children: [
        Expanded(child: Text(label, style: TextStyle(color: bold ? null : context.pal.muted))),
        Text(value, style: TextStyle(fontWeight: bold ? FontWeight.w800 : FontWeight.w600, fontSize: bold ? 16 : 14)),
      ],
    ),
  );
}
