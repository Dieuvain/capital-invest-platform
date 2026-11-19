import 'package:capital_invest/api/models.dart';
import 'package:capital_invest/ui/format.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('montants en dollars, à la française', () {
    expect(formatUsd(0), '0,00 \$');
    expect(formatUsd(1250), '12,50 \$');
    expect(formatUsd(123456789), '1 234 567,89 \$');
  });

  test('saisie de montant', () {
    expect(parseUsd('12,5'), 1250);
    expect(parseUsd('12.50'), 1250);
    expect(parseUsd('200'), 20000);
    expect(parseUsd('0'), isNull);
    expect(parseUsd('12,345'), isNull);
    expect(parseUsd('abc'), isNull);
  });

  test('numéros congolais', () {
    expect(normalizePhone('0970000001'), '+243970000001');
    expect(normalizePhone('970 000 001'), '+243970000001');
    expect(normalizePhone('+243 970-000-001'), '+243970000001');
    expect(normalizePhone('12345'), isNull);
  });

  test('mêmes règles de PIN que le serveur', () {
    expect(pinProblem('2580'), isNull);
    expect(pinProblem('1111'), isNotNull);
    expect(pinProblem('1234'), isNotNull);
    expect(pinProblem('4321'), isNotNull);
    expect(pinProblem('12a4'), isNotNull);
  });

  test('code marchand lu dans un QR ou tapé à la main', () {
    expect(merchantCodeFrom('capitalinvest://pay?m=K7P2QX'), 'K7P2QX');
    expect(merchantCodeFrom(' k7p 2qx '), 'K7P2QX');
    expect(merchantCodeFrom('K7P-2QX'), 'K7P2QX');
    expect(merchantCodeFrom('https://exemple.com/?m=K7P2QX'), isNull);
    expect(merchantCodeFrom('K7P2Q'), isNull);
    expect(merchantCodeFrom('K7P2Q0'), isNull, reason: 'pas de 0 ni de O, pour éviter les confusions');
  });
}
