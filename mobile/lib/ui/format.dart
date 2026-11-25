/// Montant en centimes vers « 1 250,50 $ », à la française.
String formatUsd(int minor) {
  final negative = minor < 0;
  final abs = minor.abs();
  final units = (abs ~/ 100).toString();
  final cents = (abs % 100).toString().padLeft(2, '0');
  final grouped = StringBuffer();
  for (var i = 0; i < units.length; i++) {
    if (i > 0 && (units.length - i) % 3 == 0) grouped.write(' ');
    grouped.write(units[i]);
  }
  return '${negative ? '-' : ''}$grouped,$cents \$';
}

/// Groupe les milliers à la française : 28215 → « 28 215 ».
String _grouped(String digits) {
  final out = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) out.write(' ');
    out.write(digits[i]);
  }
  return out.toString();
}

/// Francs congolais entiers vers « 28 215 FC ».
String formatCdf(int francs) => '${francs < 0 ? '-' : ''}${_grouped(francs.abs().toString())} FC';

/// Montant dans la devise du compte : centimes de dollar (USD) ou francs entiers (CDF).
String formatMoney(int minor, String currency) => currency == 'CDF' ? formatCdf(minor) : formatUsd(minor);

/// Taux en francs pour un dollar : 2850 → « 2 850 », 2850.5 → « 2 850,5 ».
String formatRate(num rate) {
  final parts = rate.toString().split('.');
  final decimals = parts.length > 1 && int.parse(parts[1]) != 0 ? ',${parts[1].replaceAll(RegExp(r'0+$'), '')}' : '';
  return '${_grouped(parts[0])}$decimals';
}

/// Saisie « 25 000 » ou « 25000 » vers francs entiers ; null si invalide.
int? parseCdf(String input) {
  final s = input.trim().replaceAll(RegExp(r'[\s.\u00a0\u202f]'), '');
  if (!RegExp(r'^\d{1,10}$').hasMatch(s)) return null;
  final value = int.parse(s);
  return value > 0 ? value : null;
}

/// Saisie « 12,5 » ou « 12.50 » vers centimes ; null si invalide.
int? parseUsd(String input) {
  final s = input.trim().replaceAll(' ', '').replaceAll(' ', '').replaceAll(',', '.');
  final m = RegExp(r'^(\d{1,7})(?:\.(\d{1,2}))?$').firstMatch(s);
  if (m == null) return null;
  final cents = (m.group(2) ?? '').padRight(2, '0');
  final value = int.parse(m.group(1)!) * 100 + int.parse(cents);
  return value > 0 ? value : null;
}

/// Centimes vers ce que le client taperait : 2500 → « 25 », 1250 → « 12,50 ».
String formatAmountInput(int minor) =>
    minor % 100 == 0 ? '${minor ~/ 100}' : '${minor ~/ 100},${(minor % 100).toString().padLeft(2, '0')}';

/// « 0970000001 », « 970000001 » ou « +243 970 000 001 » vers +243970000001 ; null si invalide.
String? normalizePhone(String input) {
  var s = input.replaceAll(RegExp(r'[\s.-]'), '');
  if (s.startsWith('+243')) {
    s = s.substring(4);
  } else if (s.startsWith('00243')) {
    s = s.substring(5);
  } else if (s.startsWith('0')) {
    s = s.substring(1);
  }
  return RegExp(r'^[0-9]{9}$').hasMatch(s) ? '+243$s' : null;
}

/// Mêmes règles que le serveur : 4 chiffres, ni tous identiques, ni une suite.
String? pinProblem(String pin) {
  if (!RegExp(r'^[0-9]{4}$').hasMatch(pin)) return 'Le PIN fait 4 chiffres';
  if (pin.split('').toSet().length == 1) return 'PIN trop simple (chiffres identiques)';
  const asc = '0123456789';
  const desc = '9876543210';
  if (asc.contains(pin) || desc.contains(pin)) return 'PIN trop simple (suite de chiffres)';
  return null;
}

const _months = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

/// « 14:05 · 19 nov. 2026 », à l'heure du téléphone.
String formatDateTime(DateTime at) {
  final d = at.toLocal();
  return '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')} · ${formatDay(d)}';
}

/// « le 20 nov. 2026 à 14:05 », à l'heure du téléphone, pour une échéance dans une phrase.
String formatWhen(DateTime at) {
  final d = at.toLocal();
  return 'le ${formatDay(d)} à ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
}

/// « 19 nov. 2026 », à l'heure du téléphone.
String formatDay(DateTime at) {
  final d = at.toLocal();
  return '${d.day} ${_months[d.month - 1]} ${d.year}';
}
