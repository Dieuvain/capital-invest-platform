import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Premier écran : se connecter avec son PIN, ou ouvrir un compte.
class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({super.key, required this.app});

  final AppState app;

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> with ApiAction {
  late final phone = TextEditingController(text: widget.app.lastPhone?.substring(4) ?? '');
  final pin = TextEditingController();

  Future<void> _login() async {
    final p = normalizePhone(phone.text);
    if (p == null) return setState(() => error = 'Numéro congolais attendu (9 chiffres après +243).');
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final tokens = await run(
      widget.app,
      () => widget.app.api.login(phone: p, pin: pin.text, deviceId: widget.app.deviceId),
    );
    pin.clear();
    if (tokens != null) widget.app.signedInAs(tokens);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
        children: [
          const _Logo(),
          const SizedBox(height: 36),
          const Text('Bonjour !', style: TextStyle(fontSize: 34, fontWeight: FontWeight.w700, letterSpacing: -0.5)),
          const SizedBox(height: 4),
          Text('Votre argent, partout à Kolwezi.', style: TextStyle(fontSize: 16, color: context.pal.muted)),
          const SizedBox(height: 28),
          SoftCard(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ErrorBanner(error),
                PhoneField(controller: phone),
                const SizedBox(height: 12),
                PinField(controller: pin, onSubmitted: (_) => _login()),
                const SizedBox(height: 20),
                BusyButton(label: 'Se connecter', busy: busy, onPressed: _login),
                const SizedBox(height: 4),
                TextButton(
                  onPressed: () =>
                      Navigator.of(context).push(MaterialPageRoute(builder: (_) => ForgotPinScreen(app: widget.app))),
                  child: const Text('PIN oublié ?'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          OutlinedButton(
            onPressed: () =>
                Navigator.of(context).push(MaterialPageRoute(builder: (_) => SignupScreen(app: widget.app))),
            child: const Text('Ouvrir un compte'),
          ),
        ],
      ),
    ),
  );
}

class _Logo extends StatelessWidget {
  const _Logo();

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(color: Brand.ochre, borderRadius: BorderRadius.circular(14)),
        alignment: Alignment.center,
        child: const Text(
          'CI',
          style: TextStyle(color: Brand.ink, fontWeight: FontWeight.w900, fontSize: 18),
        ),
      ),
      const SizedBox(width: 12),
      const Text('Capital Invest', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
    ],
  );
}

/// Ouverture de compte en deux étapes : code SMS, puis nom et PIN.
class SignupScreen extends StatefulWidget {
  const SignupScreen({super.key, required this.app});

  final AppState app;

  @override
  State<SignupScreen> createState() => _SignupScreenState();
}

class _SignupScreenState extends State<SignupScreen> with ApiAction {
  final phone = TextEditingController();
  final code = TextEditingController();
  final name = TextEditingController();
  final pin = TextEditingController();
  final pin2 = TextEditingController();
  String? sentTo;

  Future<void> _sendCode() async {
    final p = normalizePhone(phone.text);
    if (p == null) return setState(() => error = 'Numéro congolais attendu (9 chiffres après +243).');
    final ok = await run(widget.app, () async {
      await widget.app.api.requestSignupCode(p);
      return true;
    });
    if (ok == true) setState(() => sentTo = p);
  }

  Future<void> _register() async {
    if (!RegExp(r'^[0-9]{6}$').hasMatch(code.text)) return setState(() => error = 'Le code SMS fait 6 chiffres.');
    if (name.text.trim().length < 3) return setState(() => error = 'Entrez votre nom complet.');
    final problem = pinProblem(pin.text);
    if (problem != null) return setState(() => error = problem);
    if (pin.text != pin2.text) return setState(() => error = 'Les deux PIN sont différents.');
    final tokens = await run(
      widget.app,
      () => widget.app.api.register(
        phone: sentTo!,
        code: code.text,
        fullName: name.text.trim(),
        pin: pin.text,
        deviceId: widget.app.deviceId,
      ),
    );
    if (tokens != null && mounted) {
      widget.app.signedInAs(tokens);
      Navigator.of(context).popUntil((r) => r.isFirst);
    }
  }

  @override
  Widget build(BuildContext context) => sentTo == null
      ? FormPage(
          title: 'Ouvrir un compte',
          subtitle: 'Nous envoyons un code par SMS pour vérifier que ce numéro est bien le vôtre.',
          children: [
            ErrorBanner(error),
            PhoneField(controller: phone),
            const SizedBox(height: 20),
            BusyButton(label: 'Recevoir le code', busy: busy, onPressed: _sendCode),
          ],
        )
      : FormPage(
          title: 'Ouvrir un compte',
          subtitle: 'Code envoyé au $sentTo. Il est valable 5 minutes.',
          children: [
            ErrorBanner(error),
            TextField(
              key: const ValueKey('code'),
              controller: code,
              keyboardType: TextInputType.number,
              maxLength: 6,
              decoration: const InputDecoration(labelText: 'Code SMS', counterText: ''),
            ),
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey('name'),
              controller: name,
              textCapitalization: TextCapitalization.words,
              decoration: const InputDecoration(labelText: 'Nom complet, comme sur votre pièce'),
            ),
            const SizedBox(height: 12),
            PinField(controller: pin, label: 'Choisissez un PIN'),
            const SizedBox(height: 12),
            PinField(controller: pin2, label: 'Confirmez le PIN'),
            const SizedBox(height: 8),
            Text(
              'Ni 1111, ni 1234. Ne le donnez à personne, même à un agent.',
              style: TextStyle(color: context.pal.muted, fontSize: 13),
            ),
            const SizedBox(height: 20),
            BusyButton(label: 'Créer mon compte', busy: busy, onPressed: _register),
          ],
        );
}

class ForgotPinScreen extends StatefulWidget {
  const ForgotPinScreen({super.key, required this.app});

  final AppState app;

  @override
  State<ForgotPinScreen> createState() => _ForgotPinScreenState();
}

class _ForgotPinScreenState extends State<ForgotPinScreen> with ApiAction {
  final phone = TextEditingController();
  final code = TextEditingController();
  final pin = TextEditingController();
  final pin2 = TextEditingController();
  String? sentTo;

  Future<void> _sendCode() async {
    final p = normalizePhone(phone.text);
    if (p == null) return setState(() => error = 'Numéro congolais attendu (9 chiffres après +243).');
    final ok = await run(widget.app, () async {
      await widget.app.api.requestPinResetCode(p);
      return true;
    });
    if (ok == true) setState(() => sentTo = p);
  }

  Future<void> _reset() async {
    final problem = pinProblem(pin.text);
    if (problem != null) return setState(() => error = problem);
    if (pin.text != pin2.text) return setState(() => error = 'Les deux PIN sont différents.');
    final tokens = await run(
      widget.app,
      () => widget.app.api.resetPin(phone: sentTo!, code: code.text, newPin: pin.text, deviceId: widget.app.deviceId),
    );
    if (tokens != null && mounted) {
      widget.app.signedInAs(tokens);
      Navigator.of(context).popUntil((r) => r.isFirst);
    }
  }

  @override
  Widget build(BuildContext context) => sentTo == null
      ? FormPage(
          title: 'PIN oublié',
          subtitle:
              'Nous envoyons un code par SMS. Par sécurité, les envois seront suspendus 24 heures après le changement.',
          children: [
            ErrorBanner(error),
            PhoneField(controller: phone),
            const SizedBox(height: 20),
            BusyButton(label: 'Recevoir le code', busy: busy, onPressed: _sendCode),
          ],
        )
      : FormPage(
          title: 'Nouveau PIN',
          subtitle: 'Si ce numéro a un compte, un code vient d\'être envoyé au $sentTo.',
          children: [
            ErrorBanner(error),
            TextField(
              controller: code,
              keyboardType: TextInputType.number,
              maxLength: 6,
              decoration: const InputDecoration(labelText: 'Code SMS', counterText: ''),
            ),
            const SizedBox(height: 12),
            PinField(controller: pin, label: 'Nouveau PIN'),
            const SizedBox(height: 12),
            PinField(controller: pin2, label: 'Confirmez le PIN'),
            const SizedBox(height: 20),
            BusyButton(label: 'Changer mon PIN', busy: busy, onPressed: _reset),
          ],
        );
}
