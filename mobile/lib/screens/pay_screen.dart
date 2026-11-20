import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'history_screens.dart';
import 'money_screens.dart';
import 'pot_screens.dart';

/// Ouvre l'appareil photo, ou l'outil des tests quand il est fourni.
Future<String?> scanQr(AppState app, BuildContext context) {
  final custom = app.scanQr;
  if (custom != null) return custom(context);
  return Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => const QrScannerScreen()));
}

/// Paiement chez un marchand : scanner le QR de la caisse (ou taper son code), vérifier le nom du
/// commerce, entrer le montant, confirmer par PIN. Puis un écran de preuve à montrer au caissier.
class PayMerchantScreen extends StatefulWidget {
  const PayMerchantScreen({super.key, required this.app, this.limits, this.scanOnOpen = true});

  final AppState app;
  final Limits? limits;

  /// Ouvre tout de suite l'appareil photo : c'est le geste attendu en caisse.
  final bool scanOnOpen;

  @override
  State<PayMerchantScreen> createState() => _PayMerchantScreenState();
}

class _PayMerchantScreenState extends State<PayMerchantScreen> with ApiAction {
  final code = TextEditingController();
  final amount = TextEditingController();
  final note = TextEditingController();
  final pin = TextEditingController();
  MerchantInfo? merchant;
  int? amountMinor;
  MerchantPaymentResult? done;

  /// Gardée de la vérification jusqu'au paiement : un nouvel essai après une coupure ne paie pas deux fois.
  String _key = newIdempotencyKey();

  @override
  void initState() {
    super.initState();
    if (widget.scanOnOpen) WidgetsBinding.instance.addPostFrameCallback((_) => _scan());
  }

  Future<void> _scan() async {
    final raw = await scanQr(widget.app, context);
    if (raw == null || !mounted) return;
    // Le QR « Recevoir » d'un client : c'est un envoi, pas un paiement marchand.
    final person = ReceiveQr.parse(raw);
    if (person != null) {
      unawaited(
        Navigator.of(context).pushReplacement(
          MaterialPageRoute<void>(builder: (_) => TransferScreen(app: widget.app, limits: widget.limits, scanned: person)),
        ),
      );
      return;
    }
    // Le QR d'une cagnotte : on participe, ce n'est pas un paiement marchand.
    final pot = raw.trim().startsWith('capitalinvest://pot') ? potCodeFrom(raw) : null;
    if (pot != null) {
      unawaited(
        Navigator.of(context).pushReplacement(MaterialPageRoute<void>(builder: (_) => ContributeScreen(app: widget.app, code: pot))),
      );
      return;
    }
    final c = merchantCodeFrom(raw);
    if (c == null) return setState(() => error = 'Ce QR n\'est pas un QR de paiement Capital Invest.');
    code.text = '${c.substring(0, 3)} ${c.substring(3)}';
    await _find(c);
  }

  Future<void> _typed() async {
    final c = merchantCodeFrom(code.text);
    if (c == null) return setState(() => error = 'Le code marchand a 6 lettres et chiffres, par exemple K7P 2QX.');
    await _find(c);
  }

  Future<void> _find(String c) async {
    final m = await run(widget.app, () => widget.app.api.merchant(c));
    if (m != null) setState(() => merchant = m);
  }

  void _review() {
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 25 ou 12,50.');
    final l = widget.limits;
    if (l != null && minor > l.perTransactionMinor) {
      return setState(() => error = 'Votre plafond par opération est de ${formatUsd(l.perTransactionMinor)}.');
    }
    setState(() {
      error = null;
      amountMinor = minor;
      _key = newIdempotencyKey(); // une nouvelle vérification est un nouveau paiement
    });
  }

  Future<void> _pay() async {
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final r = await run(
      widget.app,
      () => widget.app.api.payMerchant(
        code: merchant!.code,
        amountMinor: amountMinor!,
        pin: pin.text,
        idempotencyKey: _key,
        note: note.text.trim(),
      ),
    );
    pin.clear();
    if (r != null) setState(() => done = r);
  }

  @override
  Widget build(BuildContext context) {
    final d = done;
    if (d != null) return _proof(d);
    final m = merchant;
    if (m == null) return _findMerchant();
    final a = amountMinor;
    if (a == null) return _enterAmount(m);
    return FormPage(
      title: 'Vérifier le paiement',
      children: [
        ErrorBanner(error),
        SoftCard(
          child: Column(
            children: [
              _Row('Marchand', m.name),
              _Row('Code', m.code),
              _Row('Montant', formatUsd(a)),
              _Row('Frais', 'Aucun'),
              Divider(height: 28, color: context.pal.divider),
              _Row('Total débité', formatUsd(a), strong: true),
            ],
          ),
        ),
        const SizedBox(height: 24),
        PinField(controller: pin, label: 'PIN pour confirmer', autofocus: true, onSubmitted: (_) => _pay()),
        const SizedBox(height: 20),
        BusyButton(label: 'Payer ${formatUsd(a)}', busy: busy, onPressed: _pay),
        TextButton(onPressed: () => setState(() => amountMinor = null), child: const Text('Modifier')),
      ],
    );
  }

  Widget _findMerchant() => FormPage(
    title: 'Payer',
    subtitle: 'Chez un marchand Capital Invest : scannez le QR de la caisse. Sans frais pour vous.',
    children: [
      ErrorBanner(error),
      FilledButton.icon(
        key: const ValueKey('scan'),
        onPressed: busy ? null : _scan,
        icon: const Icon(Icons.qr_code_scanner_rounded),
        label: const Text('Scanner le QR'),
      ),
      const SizedBox(height: 24),
      Text('Ou tapez le code écrit sous le QR', style: TextStyle(color: context.pal.muted)),
      const SizedBox(height: 8),
      TextField(
        key: const ValueKey('merchant-code'),
        controller: code,
        textCapitalization: TextCapitalization.characters,
        decoration: const InputDecoration(labelText: 'Code marchand', hintText: 'K7P 2QX'),
        onSubmitted: (_) => _typed(),
      ),
      const SizedBox(height: 12),
      BusyButton(label: 'Continuer', busy: busy, onPressed: _typed),
    ],
  );

  Widget _enterAmount(MerchantInfo m) => FormPage(
    title: 'Payer',
    children: [
      ErrorBanner(error),
      SoftCard(
        child: Row(
          children: [
            const IconTile(Icons.storefront_rounded, accent: true),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(m.name, key: const ValueKey('merchant-name'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
                  Text(
                    [?m.category, ?m.city, m.code].join(' · '),
                    style: TextStyle(color: context.pal.muted, fontSize: 13),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 8),
      Text(
        'Vérifiez que c\'est bien le commerce où vous êtes.',
        style: TextStyle(color: context.pal.muted, fontSize: 13),
      ),
      const SizedBox(height: 16),
      TextField(
        key: const ValueKey('amount'),
        controller: amount,
        autofocus: true,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        decoration: const InputDecoration(labelText: 'Montant à payer', suffixText: '\$'),
      ),
      const SizedBox(height: 12),
      TextField(
        controller: note,
        maxLength: 140,
        decoration: const InputDecoration(labelText: 'Référence (facultatif)', hintText: 'N° de facture, table…'),
      ),
      const SizedBox(height: 12),
      BusyButton(label: 'Continuer', busy: busy, onPressed: _review),
      TextButton(
        onPressed: () => setState(() {
          merchant = null;
          code.clear();
        }),
        child: const Text('Autre marchand'),
      ),
    ],
  );

  /// Ce que le caissier regarde : grand montant, nom du commerce, heure, numéro de reçu.
  Widget _proof(MerchantPaymentResult d) => FormPage(
    title: 'Paiement effectué',
    children: [
      SoftCard(
        padding: const EdgeInsets.fromLTRB(20, 28, 20, 24),
        child: Column(
          children: [
            const Icon(Icons.check_circle, size: 72, color: Brand.success),
            const SizedBox(height: 12),
            Text(
              formatUsd(d.amountMinor),
              key: const ValueKey('paid-amount'),
              style: const TextStyle(fontSize: 36, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 4),
            Text(
              'payés à ${d.merchantName}',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 14),
            Text(
              '${formatDateTime(d.createdAt)}\nReçu ${d.receiptNumber}',
              textAlign: TextAlign.center,
              style: TextStyle(color: context.pal.muted),
            ),
          ],
        ),
      ),
      const SizedBox(height: 12),
      Text(
        'Montrez cet écran au caissier. Le marchand reçoit aussi un SMS de confirmation.',
        textAlign: TextAlign.center,
        style: TextStyle(color: context.pal.muted, fontSize: 13),
      ),
      const SizedBox(height: 20),
      FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
      TextButton(
        onPressed: () => Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => ReceiptScreen(app: widget.app, id: d.transactionId)),
        ),
        child: const Text('Voir le reçu'),
      ),
    ],
  );
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value, {this.strong = false});

  final String label;
  final String value;
  final bool strong;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      children: [
        Text(label, style: TextStyle(color: strong ? context.pal.text : context.pal.muted, fontSize: 16)),
        const SizedBox(width: 16),
        Expanded(
          child: Text(
            value,
            textAlign: TextAlign.right,
            style: TextStyle(fontSize: strong ? 20 : 16, fontWeight: strong ? FontWeight.w800 : FontWeight.w600),
          ),
        ),
      ],
    ),
  );
}

/// Appareil photo plein écran : renvoie le premier QR lu.
class QrScannerScreen extends StatefulWidget {
  const QrScannerScreen({super.key});

  @override
  State<QrScannerScreen> createState() => _QrScannerScreenState();
}

class _QrScannerScreenState extends State<QrScannerScreen> {
  final controller = MobileScannerController(formats: const [BarcodeFormat.qrCode]);
  bool _done = false;

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  void _detected(BarcodeCapture capture) {
    final value = capture.barcodes.map((b) => b.rawValue).nonNulls.firstOrNull;
    if (value == null || _done) return;
    _done = true;
    Navigator.of(context).pop(value);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: Colors.black,
    appBar: AppBar(
      backgroundColor: Colors.black,
      foregroundColor: Colors.white,
      title: const Text('Scanner le QR du marchand'),
      actions: [
        IconButton(
          tooltip: 'Lampe',
          icon: const Icon(Icons.flashlight_on_rounded),
          onPressed: () => controller.toggleTorch(),
        ),
      ],
    ),
    body: Stack(
      fit: StackFit.expand,
      children: [
        MobileScanner(
          controller: controller,
          onDetect: _detected,
          errorBuilder: (context, error) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                error.errorCode == MobileScannerErrorCode.permissionDenied
                    ? 'Autorisez l\'appareil photo dans les réglages du téléphone, ou revenez en arrière et tapez le code du marchand.'
                    : 'L\'appareil photo ne répond pas. Revenez en arrière et tapez le code du marchand.',
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white, fontSize: 16),
              ),
            ),
          ),
        ),
        Center(
          child: Container(
            width: 240,
            height: 240,
            decoration: BoxDecoration(
              border: Border.all(color: Brand.ochre, width: 3),
              borderRadius: BorderRadius.circular(24),
            ),
          ),
        ),
        const Positioned(
          left: 24,
          right: 24,
          bottom: 48,
          child: Text(
            'Placez le QR affiché à la caisse dans le cadre.',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white, fontSize: 15),
          ),
        ),
      ],
    ),
  );
}
