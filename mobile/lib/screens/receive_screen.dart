import 'package:material_ui/material_ui.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// « +243970000001 » vers « +243 970 000 001 », plus facile à dicter.
String spacedPhone(String phone) => phone.length == 13
    ? '${phone.substring(0, 4)} ${phone.substring(4, 7)} ${phone.substring(7, 10)} ${phone.substring(10)}'
    : phone;

/// Mon QR pour recevoir : un proche le scanne dans « Payer » et l'envoi s'ouvre avec mon numéro,
/// et le montant si je l'ai choisi. Le QR ne contient que le numéro et le montant, rien de secret.
class ReceiveScreen extends StatefulWidget {
  const ReceiveScreen({super.key, required this.app});

  final AppState app;

  @override
  State<ReceiveScreen> createState() => _ReceiveScreenState();
}

class _ReceiveScreenState extends State<ReceiveScreen> {
  final amount = TextEditingController();
  int? amountMinor;
  String? error;

  void _amountChanged(String text) => setState(() {
    if (text.trim().isEmpty) {
      amountMinor = null;
      error = null;
    } else {
      amountMinor = parseUsd(text);
      error = amountMinor == null ? 'Entrez un montant, par exemple 25 ou 12,50.' : null;
    }
  });

  Future<void> _share() {
    final c = widget.app.customer!;
    final how = amountMinor == null ? '' : ' ${formatUsd(amountMinor!)}';
    return widget.app.share(
      'Pour m\'envoyer$how sur Capital Invest : ${spacedPhone(c.phone)} (${c.fullName}). '
          'Dans l\'app, touchez Envoyer et tapez ce numéro, ou scannez mon QR avec Payer.',
      'Mon numéro Capital Invest',
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.app.customer!;
    final qr = ReceiveQr(c.phone, amountMinor);
    return FormPage(
      title: 'Recevoir',
      subtitle: 'Faites scanner ce QR avec « Payer » dans l\'app Capital Invest : l\'envoi s\'ouvre avec votre numéro.',
      children: [
        SoftCard(
          child: Column(
            children: [
              // Fond blanc même en mode sombre : les lecteurs de QR lisent mieux le noir sur blanc.
              Container(
                key: const ValueKey('receive-qr'),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18)),
                child: QrImageView(
                  data: qr.payload,
                  size: 220,
                  backgroundColor: Colors.white,
                  eyeStyle: const QrEyeStyle(eyeShape: QrEyeShape.square, color: Brand.ink),
                  dataModuleStyle: const QrDataModuleStyle(dataModuleShape: QrDataModuleShape.square, color: Brand.ink),
                ),
              ),
              const SizedBox(height: 16),
              Text(c.fullName, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 2),
              Text(spacedPhone(c.phone), style: TextStyle(color: context.pal.muted)),
              if (amountMinor != null) ...[
                const SizedBox(height: 10),
                Text(
                  formatUsd(amountMinor!),
                  key: const ValueKey('receive-amount'),
                  style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 16),
        ErrorBanner(error),
        TextField(
          key: const ValueKey('receive-amount-field'),
          controller: amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          onChanged: _amountChanged,
          decoration: const InputDecoration(labelText: 'Montant demandé (facultatif)', suffixText: '\$'),
        ),
        const SizedBox(height: 6),
        Text(
          'Avec un montant, celui qui scanne n\'a plus qu\'à vérifier votre nom et confirmer avec son PIN.',
          style: TextStyle(color: context.pal.muted, fontSize: 13),
        ),
        const SizedBox(height: 16),
        OutlinedButton.icon(
          onPressed: _share,
          icon: const Icon(Icons.share_rounded),
          label: const Text('Partager mon numéro'),
        ),
      ],
    );
  }
}
