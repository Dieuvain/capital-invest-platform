import 'dart:typed_data';

import 'package:material_ui/material_ui.dart';
import 'package:image_picker/image_picker.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

class Photo {
  Photo(this.bytes, {required this.png});

  final Uint8List bytes;
  final bool png;
}

/// Prise de photo. L'implémentation réelle ouvre l'appareil ; les tests en fournissent une autre.
abstract class PhotoSource {
  Future<Photo?> take({required bool selfie});
}

class ImagePickerPhotoSource implements PhotoSource {
  final _picker = ImagePicker();

  @override
  Future<Photo?> take({required bool selfie}) async {
    final file = await _picker.pickImage(
      source: ImageSource.camera,
      preferredCameraDevice: selfie ? CameraDevice.front : CameraDevice.rear,
      maxWidth: 1600,
      imageQuality: 85, // JPEG compressé : moins de 1 Mo, envoi rapide même en 3G
    );
    if (file == null) return null;
    return Photo(await file.readAsBytes(), png: file.name.toLowerCase().endsWith('.png'));
  }
}

class KycScreen extends StatefulWidget {
  const KycScreen({super.key, required this.app, required this.photos});

  final AppState app;
  final PhotoSource photos;

  @override
  State<KycScreen> createState() => _KycScreenState();
}

class _KycScreenState extends State<KycScreen> with ApiAction {
  KycState? state;
  String idType = 'VOTER_CARD';
  final idNumber = TextEditingController();
  DateTime? birthDate;
  final Map<String, Photo> photos = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = await run(widget.app, () => widget.app.api.kyc());
    if (s != null) setState(() => state = s);
  }

  List<(String, String)> get _needed => [
    ('ID_FRONT', 'Recto de la pièce'),
    if (idType == 'VOTER_CARD') ('ID_BACK', 'Verso de la carte'),
    ('SELFIE', 'Selfie, visage bien éclairé'),
  ];

  Future<void> _take(String kind) async {
    final p = await widget.photos.take(selfie: kind == 'SELFIE');
    if (p != null) setState(() => photos[kind] = p);
  }

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final d = await showDatePicker(
      context: context,
      initialDate: DateTime(now.year - 25),
      firstDate: DateTime(1920),
      lastDate: now,
      helpText: 'Date de naissance',
    );
    if (d != null) setState(() => birthDate = d);
  }

  Future<void> _submit() async {
    final missing = _needed.where((n) => !photos.containsKey(n.$1)).map((n) => n.$2).toList();
    if (missing.isNotEmpty) return setState(() => error = 'Photo manquante : ${missing.join(', ')}.');
    if (idNumber.text.trim().isEmpty) return setState(() => error = 'Entrez le numéro de la pièce.');
    if (birthDate == null) return setState(() => error = 'Choisissez votre date de naissance.');
    final api = widget.app.api;
    final s = await run(widget.app, () async {
      for (final n in _needed) {
        final p = photos[n.$1]!;
        await api.uploadKycDocument(n.$1, p.bytes, png: p.png);
      }
      return api.submitKyc(idType: idType, idNumber: idNumber.text.trim(), birthDate: birthDate!);
    });
    if (s != null) setState(() => state = s);
  }

  @override
  Widget build(BuildContext context) {
    final s = state;
    if (s == null) {
      return FormPage(
        title: 'Niveau 2',
        children: [
          ErrorBanner(error),
          if (busy) const Center(child: CircularProgressIndicator()),
        ],
      );
    }
    if (s.kycLevel >= 2) {
      return _done(Icons.verified, Brand.success, 'Vous êtes au niveau 2', 'Vos plafonds sont relevés.');
    }
    if (s.requestStatus == 'PENDING') {
      return _done(
        Icons.hourglass_top,
        Brand.ochre,
        'Demande en cours d\'examen',
        'Un agent Capital Invest vérifie vos pièces. Vous recevrez un SMS dès la décision.',
      );
    }
    final dateText = birthDate == null
        ? 'Date de naissance'
        : '${birthDate!.day.toString().padLeft(2, '0')}/${birthDate!.month.toString().padLeft(2, '0')}/${birthDate!.year}';
    return FormPage(
      title: 'Passer au niveau 2',
      subtitle: 'Avec votre pièce d\'identité et un selfie. Vos photos sont chiffrées et vues seulement par un agent habilité.',
      children: [
        if (s.requestStatus == 'REJECTED')
          ErrorBanner(
            'Demande précédente refusée : ${s.rejectionReason ?? 'motif non précisé'}. Vous pouvez recommencer.',
          ),
        ErrorBanner(error),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'VOTER_CARD', label: Text('Carte d\'électeur')),
            ButtonSegment(value: 'PASSPORT', label: Text('Passeport')),
          ],
          selected: {idType},
          onSelectionChanged: (v) => setState(() => idType = v.first),
        ),
        const SizedBox(height: 16),
        TextField(
          key: const ValueKey('idNumber'),
          controller: idNumber,
          textCapitalization: TextCapitalization.characters,
          decoration: const InputDecoration(labelText: 'Numéro de la pièce'),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          key: const ValueKey('birthDate'),
          onPressed: _pickDate,
          icon: const Icon(Icons.cake_outlined),
          label: Text(dateText),
        ),
        const SizedBox(height: 20),
        for (final n in _needed) ...[
          _PhotoTile(label: n.$2, photo: photos[n.$1], onTap: () => _take(n.$1)),
          const SizedBox(height: 10),
        ],
        const SizedBox(height: 10),
        BusyButton(label: 'Envoyer ma demande', busy: busy, onPressed: _submit),
      ],
    );
  }

  Widget _done(IconData icon, Color color, String title, String text) => FormPage(
    title: 'Niveau 2',
    children: [
      const SizedBox(height: 24),
      Icon(icon, size: 72, color: color),
      const SizedBox(height: 16),
      Text(
        title,
        textAlign: TextAlign.center,
        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
      ),
      const SizedBox(height: 8),
      Text(
        text,
        textAlign: TextAlign.center,
        style: TextStyle(color: context.pal.muted, fontSize: 15),
      ),
      const SizedBox(height: 24),
      FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Retour')),
    ],
  );
}

class _PhotoTile extends StatelessWidget {
  const _PhotoTile({required this.label, required this.photo, required this.onTap});

  final String label;
  final Photo? photo;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: context.pal.surface,
    borderRadius: BorderRadius.circular(18),
    child: InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: SizedBox(
                width: 56,
                height: 56,
                child: photo == null
                    ? Container(color: context.pal.surfaceAlt, child: const Icon(Icons.photo_camera_outlined))
                    : Image.memory(photo!.bytes, fit: BoxFit.cover, errorBuilder: (_, _, _) => const Icon(Icons.image)),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
            ),
            Icon(
              photo == null ? Icons.add_a_photo_outlined : Icons.check_circle,
              color: photo == null ? context.pal.text : Brand.success,
            ),
          ],
        ),
      ),
    ),
  );
}
