import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import '../api/api_client.dart';
import '../app_state.dart';
import 'messages.dart';
import 'theme.dart';

/// Champ PIN : 4 chiffres masqués, clavier numérique.
class PinField extends StatelessWidget {
  const PinField({super.key, required this.controller, this.label = 'PIN', this.autofocus = false, this.onSubmitted});

  final TextEditingController controller;
  final String label;
  final bool autofocus;
  final ValueChanged<String>? onSubmitted;

  @override
  Widget build(BuildContext context) => TextField(
        key: ValueKey('pin-$label'),
        controller: controller,
        autofocus: autofocus,
        obscureText: true,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(4)],
        style: const TextStyle(fontSize: 24, letterSpacing: 12),
        decoration: InputDecoration(labelText: label),
        onSubmitted: onSubmitted,
      );
}

class PhoneField extends StatelessWidget {
  const PhoneField({super.key, required this.controller, this.label = 'Numéro de téléphone'});

  final TextEditingController controller;
  final String label;

  @override
  Widget build(BuildContext context) => TextField(
        key: ValueKey('phone-$label'),
        controller: controller,
        keyboardType: TextInputType.phone,
        decoration: InputDecoration(labelText: label, prefixText: '+243 ', hintText: '970 000 001'),
      );
}

class ErrorBanner extends StatelessWidget {
  const ErrorBanner(this.message, {super.key});

  final String? message;

  @override
  Widget build(BuildContext context) {
    if (message == null) return const SizedBox.shrink();
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: const Color(0xFFFBE9E7), borderRadius: BorderRadius.circular(12)),
      child: Row(children: [
        const Icon(Icons.error_outline, color: Brand.danger),
        const SizedBox(width: 8),
        Expanded(child: Text(message!, style: const TextStyle(color: Brand.danger))),
      ]),
    );
  }
}

class BusyButton extends StatelessWidget {
  const BusyButton({super.key, required this.label, required this.busy, required this.onPressed});

  final String label;
  final bool busy;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => FilledButton(
        onPressed: busy ? null : onPressed,
        child: busy
            ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white))
            : Text(label),
      );
}

/// Écran à défilement avec marges, pour les formulaires.
class FormPage extends StatelessWidget {
  const FormPage({super.key, required this.title, required this.children, this.subtitle});

  final String title;
  final String? subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(title)),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
            children: [
              if (subtitle != null) ...[
                Text(subtitle!, style: const TextStyle(color: Brand.muted, fontSize: 15, height: 1.4)),
                const SizedBox(height: 24),
              ],
              ...children,
            ],
          ),
        ),
      );
}

/// Exécute un appel API : gère l'indicateur, le message d'erreur et l'expiration de session.
mixin ApiAction<T extends StatefulWidget> on State<T> {
  bool busy = false;
  String? error;

  Future<R?> run<R>(AppState app, Future<R> Function() call) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      return await call();
    } on ApiException catch (e) {
      if (e.needsLogin && app.signedIn) {
        await app.signOut(callServer: false);
        if (mounted) Navigator.of(context).popUntil((r) => r.isFirst);
        return null;
      }
      if (mounted) setState(() => error = messageFor(e));
      return null;
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
}
