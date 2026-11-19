import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'history_screens.dart';

IconData _icon(AppNotification n) => switch (n.kind) {
  'TOPUP' => Icons.bolt_rounded,
  'TRANSFER_RECEIVED' => Icons.south_west_rounded,
  'TRANSFER_SENT' => Icons.north_east_rounded,
  'WITHDRAWAL_PAID' => Icons.south_east_rounded,
  'WITHDRAWAL_REFUNDED' => Icons.undo_rounded,
  'KYC_APPROVED' || 'KYC_REJECTED' => Icons.verified_user_outlined,
  'PIN_CHANGED' => Icons.lock_outline_rounded,
  _ => Icons.notifications_none_rounded,
};

/// Notifications du client : tout ce qui a touché son argent ou son compte. Ouvrir l'écran les marque comme lues.
class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key, required this.app});

  final AppState app;

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> with ApiAction {
  NotificationInbox? inbox;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final api = widget.app.api;
    final box = await run(widget.app, api.notifications);
    if (box == null || !mounted) return;
    setState(() => inbox = box);
    // Le client les a vues : la pastille de la cloche s'éteint. Un échec ici n'empêche rien.
    if (box.unread > 0) await api.markNotificationsRead().catchError((_) {});
  }

  void _open(AppNotification n) {
    final tx = n.transactionId;
    if (tx == null) return;
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => ReceiptScreen(app: widget.app, id: tx)));
  }

  @override
  Widget build(BuildContext context) {
    final box = inbox;
    return FormPage(
      title: 'Notifications',
      subtitle: 'Vous les recevez aussi par SMS.',
      children: [
        ErrorBanner(error),
        if (box == null && error == null) const Center(child: CircularProgressIndicator()),
        if (box != null && box.items.isEmpty)
          SoftCard(
            padding: const EdgeInsets.all(20),
            child: Text('Aucune notification pour l\'instant.', style: TextStyle(color: context.pal.muted)),
          ),
        if (box != null && box.items.isNotEmpty)
          SoftCard(
            padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
            child: Column(
              children: [
                for (final n in box.items)
                  InkWell(
                    key: ValueKey('notification-${n.id}'),
                    onTap: n.transactionId == null ? null : () => _open(n),
                    borderRadius: BorderRadius.circular(14),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          IconTile(_icon(n), size: 42, accent: n.kind == 'PIN_CHANGED'),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        n.title,
                                        style: TextStyle(fontSize: 15, fontWeight: n.read ? FontWeight.w600 : FontWeight.w800),
                                      ),
                                    ),
                                    if (!n.read)
                                      Container(
                                        key: ValueKey('unread-${n.id}'),
                                        width: 8,
                                        height: 8,
                                        decoration: const BoxDecoration(color: Brand.ochreDeep, shape: BoxShape.circle),
                                      ),
                                  ],
                                ),
                                const SizedBox(height: 2),
                                Text(n.body, style: const TextStyle(fontSize: 14, height: 1.35)),
                                const SizedBox(height: 4),
                                Text(formatDateTime(n.createdAt), style: TextStyle(color: context.pal.muted, fontSize: 12)),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}
