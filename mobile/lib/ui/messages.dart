import '../api/api_client.dart';

/// Messages lisibles pour les codes d'erreur du serveur. Le détail du serveur sert de secours.
String messageFor(Object error) {
  if (error is! ApiException) return 'Une erreur est survenue. Réessayez.';
  return switch (error.code) {
    'NETWORK' => error.detail,
    'INVALID_CREDENTIALS' => 'Numéro ou PIN incorrect.',
    'PIN_LOCKED' => 'PIN bloqué après 5 erreurs. Réessayez dans 15 minutes ou changez votre PIN.',
    'SESSION_EXPIRED' || 'SESSION_INVALID' || 'AUTH_REQUIRED' => 'Session expirée. Entrez votre PIN.',
    'OTP_INVALID' => 'Code invalide ou expiré.',
    'OTP_RATE_LIMITED' => 'Trop de codes demandés. Réessayez dans 10 minutes.',
    'PHONE_ALREADY_REGISTERED' => 'Ce numéro a déjà un compte. Connectez-vous.',
    'PIN_TOO_SIMPLE' || 'PIN_FORMAT' => 'Ce PIN est trop facile à deviner.',
    'INSUFFICIENT_FUNDS' => 'Solde insuffisant pour ce montant et les frais.',
    'RECIPIENT_NOT_FOUND' => 'Aucun client Capital Invest avec ce numéro.',
    'SAME_ACCOUNT' => 'Vous ne pouvez pas vous envoyer de l\'argent.',
    'LIMIT_PER_TRANSACTION' => 'Montant au-dessus de votre plafond par opération.',
    'LIMIT_DAILY' => 'Plafond journalier d\'envoi atteint.',
    'LIMIT_MAX_BALANCE' => 'Cette recharge dépasserait votre solde maximal.',
    'RECIPIENT_LIMIT_REACHED' => 'Le destinataire ne peut pas recevoir ce montant.',
    'PIN_RECENTLY_RESET' => 'Par sécurité, les envois sont suspendus 24 h après un changement de PIN.',
    'MERCHANT_NOT_FOUND' => 'Aucun marchand avec ce code. Vérifiez le code sous le QR.',
    'MERCHANT_NOT_ACTIVE' => 'Ce marchand n\'accepte pas de paiement pour le moment.',
    'REQUEST_EXPIRED' => 'Cette demande a expiré.',
    'REQUEST_CLOSED' => 'Cette demande a déjà été payée, refusée ou annulée.',
    'KYC_UNDERAGE' => 'Il faut avoir 18 ans pour le niveau 2.',
    'KYC_REQUEST_PENDING' => 'Votre demande est déjà en cours d\'examen.',
    'DOCUMENT_TYPE_INVALID' => 'Photo JPEG ou PNG attendue.',
    'DOCUMENT_TOO_LARGE' => 'Photo trop lourde (5 Mo au plus).',
    _ => error.detail,
  };
}
