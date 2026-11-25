import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'complaint_screens.dart';

/// Titre d'une opération, tel que le client le comprend.
String historyTitle(HistoryItem i) => switch (i.kind) {
  'TOPUP' => i.operatorLabel == null ? 'Recharge' : 'Recharge ${i.operatorLabel}',
  'TRANSFER_SENT' => 'Envoi à ${i.counterpartyName ?? 'un client'}',
  'TRANSFER_RECEIVED' => 'Reçu de ${i.counterpartyName ?? 'un client'}',
  'WITHDRAWAL' => 'Retrait vers ${i.operatorLabel ?? 'mobile money'}',
  'WITHDRAWAL_REFUND' => 'Retrait remboursé',
  'TRANSFER_REFUND' => 'Envoi à ${i.counterpartyName ?? 'un proche'} remboursé',
  'MERCHANT_PAYMENT' => 'Paiement à ${i.counterpartyName ?? 'un marchand'}',
  'AIRTIME' => 'Crédit ${i.network?.label ?? 'téléphone'}',
  'AIRTIME_REFUND' => 'Crédit non envoyé, remboursé',
  'BILL_PAYMENT' => 'Facture ${i.counterpartyName ?? ''}'.trim(),
  'BILL_REFUND' => 'Facture non payée, remboursée',
  'SAVINGS_DEPOSIT' => 'Mis de côté',
  'SAVINGS_WITHDRAWAL' => 'Repris de l\'épargne',
  'POT_CONTRIBUTION' => 'Cagnotte « ${i.counterpartyName ?? ''} »',
  'POT_PAYOUT' => 'Cagnotte « ${i.counterpartyName ?? ''} » versée',
  'CARD_PAYMENT' => 'Carte · ${i.counterpartyName ?? 'commerçant'}',
  'CARD_REFUND' => 'Carte · paiement annulé par ${i.counterpartyName ?? 'le commerçant'}',
  'CARD_FEE' => 'Frais de carte',
  'CREDIT_DISBURSEMENT' => 'Crédit express versé',
  'CREDIT_REPAYMENT' => 'Remboursement du crédit',
  _ => 'Correction de solde',
};

/// État à afficher, quand l'opération n'est pas simplement terminée.
String? historyStatus(HistoryItem i) => switch (i.status) {
  'PENDING' when i.kind == 'TRANSFER_SENT' => 'En attente de son inscription',
  'PENDING' => 'En cours',
  'REVIEW' => 'En vérification',
  'FAILED' => 'Refusé, remboursé',
  _ => null,
};

IconData historyIcon(HistoryItem i) => switch (i.kind) {
  'TOPUP' => Icons.bolt_rounded,
  'TRANSFER_SENT' => Icons.north_east_rounded,
  'TRANSFER_RECEIVED' => Icons.south_west_rounded,
  'WITHDRAWAL' => Icons.south_east_rounded,
  'WITHDRAWAL_REFUND' => Icons.undo_rounded,
  'MERCHANT_PAYMENT' => Icons.storefront_rounded,
  'AIRTIME' => Icons.phone_android_rounded,
  'AIRTIME_REFUND' || 'BILL_REFUND' || 'TRANSFER_REFUND' => Icons.undo_rounded,
  'BILL_PAYMENT' => Icons.receipt_rounded,
  'SAVINGS_DEPOSIT' || 'SAVINGS_WITHDRAWAL' => Icons.savings_outlined,
  'POT_CONTRIBUTION' || 'POT_PAYOUT' => Icons.volunteer_activism_outlined,
  'CARD_PAYMENT' || 'CARD_FEE' => Icons.credit_card_rounded,
  'CARD_REFUND' => Icons.undo_rounded,
  'CREDIT_DISBURSEMENT' || 'CREDIT_REPAYMENT' => Icons.payments_outlined,
  _ => Icons.tune_rounded,
};

String _signed(int net) => '${net > 0 ? '+' : ''}${formatUsd(net)}';

/// Texte du reçu, envoyé tel quel par WhatsApp ou SMS : lisible sans l'app.
String receiptText(Receipt r) {
  final i = r.item;
  final lines = <String>[
    'Reçu Capital Invest',
    'N° ${i.receiptNumber}',
    formatDateTime(i.createdAt),
    '',
    historyTitle(i),
    if (i.counterpartyPhone != null) 'Numéro : ${i.counterpartyPhone}',
    if (i.merchantCode != null) 'Code marchand : ${i.merchantCode}',
    if (i.msisdn != null && i.kind == 'WITHDRAWAL') 'Vers : ${i.msisdn}',
    if (i.msisdn != null && i.kind == 'AIRTIME') 'Numéro rechargé : ${i.msisdn}',
    if (i.billReference != null) 'Compteur ou abonné : ${i.billReference}',
    if (i.token != null) 'Code du compteur : ${i.token}',
    'Montant : ${formatUsd(i.amountMinor)}',
    if (i.feeMinor != 0) '${i.kind.endsWith('_REFUND') ? 'Frais rendus' : 'Frais'} : ${formatUsd(i.feeMinor)}',
    if (i.feeMinor != 0) '${i.isIn ? 'Total crédité' : 'Total débité'} : ${formatUsd(i.netMinor.abs())}',
    if (i.note != null) 'Message : ${i.note}',
    if (historyStatus(i) != null) 'État : ${historyStatus(i)}',
    '',
    'Titulaire : ${r.holderName} (${r.holderPhone})',
  ];
  return lines.join('\n');
}

/// Une ligne d'historique : quoi, quand, combien.
class HistoryTile extends StatelessWidget {
  const HistoryTile(this.item, {super.key, this.onTap});

  final HistoryItem item;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final status = historyStatus(item);
    final failed = item.status == 'FAILED';
    return InkWell(
      key: ValueKey('history-${item.id}'),
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Row(
          children: [
            IconTile(historyIcon(item), size: 42),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    historyTitle(item),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                  ),
                  Text(
                    [formatDateTime(item.createdAt), ?status].join(' · '),
                    style: TextStyle(color: status == null ? context.pal.muted : Brand.ochreDeep, fontSize: 12),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Text(
              _signed(item.netMinor),
              style: TextStyle(
                fontWeight: FontWeight.w700,
                fontSize: 15,
                color: failed ? context.pal.muted : (item.isIn ? Brand.success : null),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Historique complet, filtrable par type, chargé page par page.
class HistoryView extends StatefulWidget {
  const HistoryView({super.key, required this.app, required this.onOpen});

  final AppState app;
  final ValueChanged<HistoryItem> onOpen;

  @override
  State<HistoryView> createState() => _HistoryViewState();
}

class _HistoryViewState extends State<HistoryView> with ApiAction {
  static const filters = {null: 'Tout', 'TOPUP': 'Recharges', 'TRANSFER': 'Envois', 'WITHDRAWAL': 'Retraits', 'PAYMENT': 'Paiements', 'SAVINGS': 'Épargne', 'CREDIT': 'Crédit'};

  String? category;
  List<HistoryItem>? items;
  String? next;
  final search = TextEditingController();
  Timer? _typing;

  /// Texte cherché, null sans recherche.
  String? get _query => search.text.trim().isEmpty ? null : search.text.trim();

  @override
  void initState() {
    super.initState();
    _load(reset: true);
  }

  @override
  void dispose() {
    _typing?.cancel();
    search.dispose();
    super.dispose();
  }

  /// On cherche quand le client s'arrête de taper, pas à chaque lettre.
  void _searchChanged(String _) {
    _typing?.cancel();
    _typing = Timer(const Duration(milliseconds: 400), _reload);
    setState(() {}); // bouton d'effacement
  }

  void _clearSearch() {
    _typing?.cancel();
    search.clear();
    _reload();
  }

  void _reload() {
    if (!mounted) return;
    setState(() {
      items = null;
      next = null;
    });
    _load(reset: true);
  }

  Future<void> _load({required bool reset}) async {
    final wanted = category;
    final query = _query;
    final page = await run(
      widget.app,
      () => widget.app.api.history(category: wanted, cursor: reset ? null : next, limit: 20, search: query),
    );
    if (page == null || !mounted || wanted != category || query != _query) return;
    setState(() {
      items = [if (!reset) ...?items, ...page.items];
      next = page.nextCursor;
    });
  }

  void _filter(String? c) {
    if (c == category) return;
    setState(() {
      category = c;
      items = null;
      next = null;
    });
    _load(reset: true);
  }

  @override
  Widget build(BuildContext context) {
    final list = items;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          key: const ValueKey('history-search'),
          controller: search,
          onChanged: _searchChanged,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) {
            _typing?.cancel();
            _reload();
          },
          decoration: InputDecoration(
            hintText: 'Nom, numéro, motif, montant ou n° de reçu',
            prefixIcon: const Icon(Icons.search_rounded),
            suffixIcon: search.text.isEmpty
                ? null
                : IconButton(tooltip: 'Effacer', icon: const Icon(Icons.close_rounded), onPressed: _clearSearch),
          ),
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final MapEntry(key: c, value: label) in filters.entries)
              ChoiceChip(
                key: ValueKey('filter-${c ?? 'ALL'}'),
                label: Text(label),
                selected: category == c,
                onSelected: (_) => _filter(c),
              ),
          ],
        ),
        const SizedBox(height: 14),
        ErrorBanner(error),
        SoftCard(
          padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (list == null && busy)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 24),
                  child: Center(child: CircularProgressIndicator()),
                ),
              if (list != null && list.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 18),
                  child: Text(
                    _query != null
                        ? 'Aucune opération ne correspond à « $_query ».'
                        : category == null
                        ? 'Aucune opération pour l\'instant. Commencez par une recharge.'
                        : 'Aucune opération de ce type.',
                    style: TextStyle(color: context.pal.muted),
                  ),
                ),
              for (final i in list ?? const <HistoryItem>[]) HistoryTile(i, onTap: () => widget.onOpen(i)),
            ],
          ),
        ),
        if (next != null) ...[
          const SizedBox(height: 12),
          OutlinedButton(
            onPressed: busy ? null : () => _load(reset: false),
            child: Text(busy ? 'Chargement…' : 'Voir plus'),
          ),
        ],
      ],
    );
  }
}

/// Reçu d'une opération, avec le partage (WhatsApp, SMS…).
class ReceiptScreen extends StatefulWidget {
  const ReceiptScreen({super.key, required this.app, required this.id});

  final AppState app;
  final String id;

  @override
  State<ReceiptScreen> createState() => _ReceiptScreenState();
}

class _ReceiptScreenState extends State<ReceiptScreen> with ApiAction {
  Receipt? receipt;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final r = await run(widget.app, () => widget.app.api.receipt(widget.id));
    if (r != null && mounted) setState(() => receipt = r);
  }

  @override
  Widget build(BuildContext context) {
    final r = receipt;
    if (r == null) {
      return FormPage(
        title: 'Reçu',
        children: [
          ErrorBanner(error),
          if (error == null) const Center(child: CircularProgressIndicator()),
        ],
      );
    }
    final i = r.item;
    final status = historyStatus(i);
    final rows = <(String, String)>[
      ('N° de reçu', i.receiptNumber),
      ('Date', formatDateTime(i.createdAt)),
      if (i.counterpartyName != null)
        (switch (i.kind) {
          'TRANSFER_RECEIVED' => 'Expéditeur',
          'MERCHANT_PAYMENT' || 'CARD_PAYMENT' || 'CARD_REFUND' => 'Marchand',
          'BILL_PAYMENT' || 'BILL_REFUND' => 'Facturier',
          _ => 'Destinataire',
        }, i.counterpartyName!),
      if (i.merchantCode != null) ('Code marchand', i.merchantCode!),
      if (i.counterpartyPhone != null) ('Numéro', i.counterpartyPhone!),
      if (i.operatorLabel != null) ('Opérateur', i.operatorLabel!),
      if (i.msisdn != null && i.kind == 'WITHDRAWAL') ('Envoyé vers', i.msisdn!),
      if (i.msisdn != null && i.kind == 'AIRTIME') ('Numéro rechargé', i.msisdn!),
      if (i.billReference != null) ('Compteur ou abonné', i.billReference!),
      if (i.token != null) ('Code du compteur', i.token!),
      ('Montant', formatUsd(i.amountMinor)),
      if (i.feeMinor != 0) (i.kind.endsWith('_REFUND') ? 'Frais rendus' : 'Frais', formatUsd(i.feeMinor)),
      if (i.feeMinor != 0) (i.isIn ? 'Total crédité' : 'Total débité', formatUsd(i.netMinor.abs())),
      if (i.note != null) ('Message', i.note!),
      ?(status == null ? null : ('État', status)),
      if (i.failureMessage != null) ('Motif', i.failureMessage!),
      ('Titulaire', r.holderName),
    ];
    return FormPage(
      title: 'Reçu',
      subtitle: historyTitle(i),
      children: [
        SoftCard(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  IconTile(historyIcon(i), accent: true),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      _signed(i.netMinor),
                      key: const ValueKey('receipt-amount'),
                      style: TextStyle(
                        fontSize: 28,
                        fontWeight: FontWeight.w800,
                        color: i.isIn ? Brand.success : null,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              const Divider(height: 1),
              for (final (label, value) in rows)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 9),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(
                        width: 120,
                        child: Text(label, style: TextStyle(color: context.pal.muted)),
                      ),
                      Expanded(
                        child: Text(value, textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w600)),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        FilledButton.icon(
          onPressed: () => widget.app.withoutLock(() => widget.app.share(receiptText(r), 'Reçu Capital Invest ${i.receiptNumber}')),
          icon: const Icon(Icons.ios_share_rounded),
          label: const Text('Partager le reçu'),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: const ValueKey('report-problem'),
          onPressed: () => Navigator.of(
            context,
          ).push(MaterialPageRoute<void>(builder: (_) => NewComplaintScreen(app: widget.app, operation: i))),
          icon: const Icon(Icons.report_problem_outlined),
          label: const Text('Signaler un problème'),
        ),
      ],
    );
  }
}
