import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'history_screens.dart';

/// Compte en francs congolais à côté du compte en dollars, et change entre les deux.
class ExchangeScreen extends StatefulWidget {
  const ExchangeScreen({super.key, required this.app, this.from = 'USD'});

  final AppState app;

  /// Sens proposé à l'ouverture : USD (dollars vers francs) ou CDF.
  final String from;

  @override
  State<ExchangeScreen> createState() => _ExchangeScreenState();
}

class _ExchangeScreenState extends State<ExchangeScreen> with ApiAction {
  final amount = TextEditingController();
  List<AccountBalance>? accounts;
  ExchangeRate? rate;
  List<HistoryItem> recent = const [];
  late String from = widget.from;

  /// Dernier change réussi, montré en haut jusqu'au suivant.
  ExchangeResult? done;

  /// Gardée pour toute la saisie : un nouvel essai après une coupure ne change pas l'argent deux fois.
  String _key = newIdempotencyKey();

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final api = widget.app.api;
    await run(widget.app, () async {
      final results = await Future.wait([api.accounts(), api.exchangeRate(), api.history(currency: 'CDF', limit: 5)]);
      if (!mounted) return;
      setState(() {
        accounts = results[0] as List<AccountBalance>;
        rate = results[1] as ExchangeRate;
        recent = (results[2] as HistoryPage).items;
      });
    });
  }

  int _balance(String currency) => accounts?.where((a) => a.currency == currency).firstOrNull?.balanceMinor ?? 0;

  String get _to => from == 'USD' ? 'CDF' : 'USD';

  int? get _typed => from == 'USD' ? parseUsd(amount.text) : parseCdf(amount.text);

  ExchangeQuote? get _preview {
    final r = rate;
    final minor = _typed;
    if (r == null || minor == null || minor < r.minFor(from)) return null;
    final q = r.quote(from, minor);
    return q.receivedMinor > 0 ? q : null;
  }

  void _changed() => setState(() {
    _key = newIdempotencyKey();
    done = null;
  });

  void _switch(String value) {
    amount.clear();
    setState(() {
      from = value;
      error = null;
      _key = newIdempotencyKey();
    });
  }

  Future<void> _submit() async {
    final r = rate;
    final minor = _typed;
    if (r == null) return;
    if (minor == null) {
      return setState(
        () => error = from == 'USD'
            ? 'Entrez un montant, par exemple 10 ou 2,50.'
            : 'Entrez un montant en francs, par exemple 25 000.',
      );
    }
    if (minor < r.minFor(from)) return setState(() => error = 'Changez au moins ${formatMoney(r.minFor(from), from)}.');
    if (minor > _balance(from)) {
      return setState(
        () => error =
            'Vous avez ${formatMoney(_balance(from), from)} sur votre compte en ${from == 'USD' ? 'dollars' : 'francs'}.',
      );
    }
    final result = await run(widget.app, () => widget.app.api.exchange(from, minor, r.cdfPerUsd, _key));
    if (!mounted) return;
    if (result == null) {
      // Le taux a peut-être changé : l'aperçu se met à jour, le client relit avant de réessayer.
      final fresh = await _quietly(widget.app.api.exchangeRate);
      if (fresh != null && mounted) setState(() => rate = fresh);
      return;
    }
    amount.clear();
    final history = await _quietly(() => widget.app.api.history(currency: 'CDF', limit: 5));
    if (!mounted) return;
    setState(() {
      done = result;
      accounts = result.accounts;
      if (history != null) recent = history.items;
      _key = newIdempotencyKey();
    });
  }

  Future<R?> _quietly<R>(Future<R> Function() call) async {
    try {
      return await call();
    } on ApiException {
      return null;
    }
  }

  void _receipt(HistoryItem i) => Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => ReceiptScreen(app: widget.app, id: i.id, currency: i.currency),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final r = rate;
    final hidden = widget.app.hideBalance;
    final preview = _preview;
    return FormPage(
      title: 'Dollars et francs',
      subtitle: 'Votre compte en francs congolais, à côté de votre compte en dollars. Changez de l\'un à l\'autre quand vous voulez.',
      children: [
        ErrorBanner(error),
        if (done != null) ...[_Done(result: done!), const SizedBox(height: 16)],
        if (accounts != null && r != null) ...[
          Row(
            children: [
              Expanded(
                child: _AccountCard(
                  label: 'Dollars',
                  value: hidden ? '•••••' : formatUsd(_balance('USD')),
                  valueKey: 'usd-balance',
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _AccountCard(
                  label: 'Francs',
                  value: hidden ? '•••••' : formatCdf(_balance('CDF')),
                  valueKey: 'cdf-balance',
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            '1 \$ = ${formatRate(r.cdfPerUsd)} FC · frais ${formatRate(r.feeBps / 100)} %',
            key: const ValueKey('exchange-rate'),
            style: TextStyle(color: context.pal.muted),
          ),
          const SizedBox(height: 16),
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'USD', label: Text('\$ vers FC')),
              ButtonSegment(value: 'CDF', label: Text('FC vers \$')),
            ],
            selected: {from},
            showSelectedIcon: false,
            onSelectionChanged: (s) => _switch(s.first),
          ),
          const SizedBox(height: 16),
          TextField(
            key: const ValueKey('amount'),
            controller: amount,
            keyboardType: TextInputType.numberWithOptions(decimal: from == 'USD'),
            decoration: InputDecoration(
              labelText: from == 'USD' ? 'Dollars à changer' : 'Francs à changer',
              suffixText: from == 'USD' ? '\$' : 'FC',
              helperText: 'Disponible : ${formatMoney(_balance(from), from)}',
            ),
            onChanged: (_) => _changed(),
          ),
          const SizedBox(height: 16),
          SoftCard(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                _Line('Frais', preview == null ? '—' : formatMoney(preview.feeMinor, from)),
                const SizedBox(height: 8),
                _Line(
                  'Vous recevez',
                  preview == null ? '—' : formatMoney(preview.receivedMinor, _to),
                  bold: true,
                  valueKey: 'exchange-received',
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),
          BusyButton(label: 'Changer', busy: busy, onPressed: _submit),
          if (recent.isNotEmpty) ...[
            const SizedBox(height: 28),
            const Text('Compte en francs', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            const SizedBox(height: 4),
            for (final i in recent) HistoryTile(i, onTap: () => _receipt(i)),
          ],
        ] else if (error == null)
          const Center(child: CircularProgressIndicator()),
      ],
    );
  }
}

class _AccountCard extends StatelessWidget {
  const _AccountCard({required this.label, required this.value, required this.valueKey});

  final String label;
  final String value;
  final String valueKey;

  @override
  Widget build(BuildContext context) => SoftCard(
    padding: const EdgeInsets.all(16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: TextStyle(color: context.pal.muted)),
        const SizedBox(height: 6),
        FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Amount(value, key: ValueKey(valueKey), size: 24),
        ),
      ],
    ),
  );
}

class _Line extends StatelessWidget {
  const _Line(this.label, this.value, {this.bold = false, this.valueKey});

  final String label;
  final String value;
  final bool bold;
  final String? valueKey;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Text(label, style: TextStyle(color: context.pal.muted)),
      ),
      Text(
        value,
        key: valueKey == null ? null : ValueKey<String>(valueKey!),
        style: TextStyle(fontWeight: bold ? FontWeight.w800 : FontWeight.w600, fontSize: bold ? 18 : 15),
      ),
    ],
  );
}

class _Done extends StatelessWidget {
  const _Done({required this.result});

  final ExchangeResult result;

  @override
  Widget build(BuildContext context) {
    final q = result.quote;
    return Container(
      key: const ValueKey('exchange-done'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: Brand.success.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(14)),
      child: Row(
        children: [
          const Icon(Icons.check_circle_rounded, color: Brand.success),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              '${formatMoney(q.amountMinor, q.from)} changés : ${formatMoney(q.receivedMinor, q.to)} reçus.',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}
