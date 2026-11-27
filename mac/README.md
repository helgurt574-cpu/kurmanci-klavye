# Kurmancî Klavye — Mac sürümü

Mac'te Türkçe yazarken Kurmancî çeviriyi gösteren bir **girdi kaynağı** (macOS'ta klavyelerin bulunduğu menü).
Android klavyesindeki sözlüğün ve çeviri kurallarının aynısını kullanır. Tamamen çevrimdışı çalışır; yazdıklarınız
bilgisayarınızdan hiçbir yere gitmez.

- macOS 13 Ventura ve sonrası
- Intel ve Apple Silicon (M1/M2/M3…) Mac'lerde çalışır
- Android uygulaması (APK) Mac'e kurulamaz; bu klasör Mac için ayrı yazılmış bir sürümdür.

> **Durum:** Bu sürüm Linux'ta hazırlandı. Sözlük ve çeviri kısmı test edildi (Android sürümüyle aynı sonuçları
> veriyor), ama Mac'e özgü kısım (girdi kaynağı ve öneri penceresi) henüz gerçek bir Mac'te derlenip denenmedi.
> Derlerken hata çıkarsa ekrandaki mesajın tamamını kopyalayıp gönderin.

---

## Kolay kurulum: hazır paketi indirin (Xcode gerekmez, ~5 dakika)

1. <https://github.com/helgurt574-cpu/kurmanci-klavye/releases> sayfasında adı **mac-v** ile başlayan sürümü
   bulun ve **KurmanciKlavye-mac.zip** dosyasını indirin.
2. **Terminal**'i açın (⌘ + Boşluk → `Terminal` → Return) ve şu satırları tek tek yapıştırıp Return'e basın:

   ```bash
   cd ~/Downloads && { [ -d KurmanciKlavye.app ] || ditto -x -k KurmanciKlavye-mac.zip . ; } && ls -d KurmanciKlavye.app
   ```

   ```bash
   mkdir -p ~/Library/Input\ Methods && rm -rf ~/Library/Input\ Methods/KurmanciKlavye.app && mv ~/Downloads/KurmanciKlavye.app ~/Library/Input\ Methods/
   ```

   ```bash
   xattr -dr com.apple.quarantine ~/Library/Input\ Methods/KurmanciKlavye.app
   ```

   ```bash
   ~/Library/Input\ Methods/KurmanciKlavye.app/Contents/MacOS/KurmanciKlavye --register
   ```

3. Aşağıdaki **4. adımla** (girdi kaynağı olarak ekleme) devam edin.

**`xattr` satırı neden gerekli?** Paket, Apple'a ücretli geliştirici hesabıyla imzalatılmadı. macOS internetten
inen imzasız programları ("Gatekeeper" denetimi) engeller ve klavye hiç açılmaz ya da "açılamıyor, geliştirici
doğrulanamadı" uyarısı çıkar. Bu satır yalnızca bu paketin üzerindeki "internetten indirildi" işaretini kaldırır.
Normal uygulamalarda sağ tık → **Aç** da aynı işi görür, ama girdi kaynakları bir pencere olarak açılmadığı için
burada Terminal yolu gerekir.

Bu paket, GitHub'ın Mac sunucularında bu klasördeki kaynak koddan otomatik derlenir
(`.github/workflows/mac.yml`). Kendiniz derlemek isterseniz aşağıdaki adımları izleyin.

---

## Kaynaktan derleyerek kurulum (yaklaşık 15–30 dakika)

### 1. Apple'ın geliştirici araçlarını yükleyin

Tam Xcode gerekmez; küçük olan "Komut Satırı Araçları" yeterli (yaklaşık 1–2 GB).

1. **Terminal**'i açın: ⌘ + Boşluk'a basın, `Terminal` yazın, Return'e basın.
2. Şunu yapıştırıp Return'e basın:

   ```bash
   xcode-select --install
   ```

3. Açılan pencerede **Yükle**'ye tıklayın, lisansı kabul edin ve bitmesini bekleyin.
   ("Zaten yüklü" derse bu adım tamam demektir.)
4. Kontrol edin; bir sürüm numarası görmelisiniz:

   ```bash
   swift --version
   ```

### 2. Projeyi indirin

<https://github.com/helgurt574-cpu/kurmanci-klavye> sayfasında yeşil **Code** düğmesi → **Download ZIP**.
İnen ZIP'e çift tıklayın; **İndirilenler** klasöründe `kurmanci-klavye-main` adlı bir klasör oluşur.

(Git kullanıyorsanız: `git clone https://github.com/helgurt574-cpu/kurmanci-klavye.git`)

### 3. Derleyip kurun

Terminal'e şunları sırayla yapıştırın (klasör adı farklıysa ona göre değiştirin):

```bash
cd ~/Downloads/kurmanci-klavye-main/mac
```

```bash
bash build.sh install
```

İlk derleme birkaç dakika sürebilir. Sonunda `Kuruldu.` yazısını görmelisiniz. Klavye
`~/Library/Input Methods/KurmanciKlavye.app` konumuna kopyalanır.

### 4. Girdi kaynağı olarak ekleyin

1.  menüsü → **Sistem Ayarları** → **Klavye**.
2. **Metin Girişi** bölümünde, **Girdi Kaynakları**'nın yanındaki **Düzenle…** düğmesine tıklayın.
3. Sol alttaki **+** düğmesine tıklayın, soldan **Türkçe**'yi seçin, sağda **Kurmancî Klavye**'yi seçip **Ekle**'ye
   tıklayın.
4. **Türkçe Q** (veya kullandığınız Türkçe klavye) listede kalsın: Kurmancî Klavye tuşları onun üzerinden okur,
   böylece ç ğ ı ö ş ü tuşları doğru çalışır.

Listede **Kurmancî Klavye** görünmüyorsa oturumu kapatıp yeniden açın (veya Mac'i yeniden başlatın) ve tekrar
deneyin.

---

## Kullanım

Menü çubuğundaki klavye simgesinden **Ku** simgeli **Kurmancî Klavye**'yi seçin. (Klavyeler arasında
🌐/fn tuşuyla ya da Control + Boşluk ile de geçebilirsiniz.)

Normal şekilde Türkçe yazın. Yazdığınız metnin altı çizili görünür ve hemen altında bir öneri listesi açılır:

- En üstte **⇄** ile başlayan satır, cümlenin tamamının çevirisidir (iki kelime yazınca çıkar).
  Örnek: `Ben eve gidiyorum` → **⇄ Ez diçim malê**
- Altındaki satırlar son kelimenin (veya kalıbın) Kurmancî karşılıklarıdır; yanlarında hangi Türkçe kelimeden
  geldikleri yazar.

| Tuş | Ne yapar |
|---|---|
| **1–9** | O numaralı öneriyi seçer (kelimenin hemen ardından; boşluktan sonra rakam normal yazılır) |
| **Tab** | İlk öneriyi (ya da oklarla seçili olanı) alır |
| **↓ / ↑** ve **Return** | Listede gezinip seçer |
| Fareyle tıklama | Tıklanan öneriyi seçer |
| **Return** (liste içinde gezinmediyseniz) | Türkçeyi olduğu gibi bırakır ve Return'ü uygulamaya iletir |
| **Esc** | Türkçeyi olduğu gibi bırakır |
| **Sil (⌫)** | Altı çizili metinden son harfi siler |

Bir öneriyi seçince altı çizili Türkçe, Kurmancî karşılığıyla değişir. Klavye sık seçtiğiniz karşılıkları öğrenir
ve bir sonraki sefer öne alır.

Türkçe harfler olmadan yazmak da olur: `guzel` → güzel → **xweşik**.

---

## Güncelleme ve kaldırma

**Güncellemek için:** projeyi yeniden indirip 3. adımı tekrarlayın.

**Kaldırmak için:** Sistem Ayarları → Klavye → Düzenle… listesinde Kurmancî Klavye'yi seçip **−** ile kaldırın,
sonra Terminal'de:

```bash
rm -rf ~/Library/Input\ Methods/KurmanciKlavye.app
```

---

## Sorun giderme

- **`swift: command not found` / `xcrun: error`** — 1. adım tamamlanmamış. `xcode-select --install`'ı tekrar
  çalıştırın.
- **"arm64 derlenemedi" uyarısı** — Önemli değil; klavye yine bu Mac için (Intel) derlenir ve çalışır.
- **Türkçe harfler (ş, ğ, ı…) yanlış çıkıyor** — Girdi kaynakları listesinde bir Türkçe klavye olduğundan emin
  olun. Birden fazla Türkçe klavye (ör. hem Türkçe Q hem Türkçe F) varsa, Kurmancî Klavye listede ilk bulduğunu
  kullanır; yalnızca kullandığınızı bırakıp başka bir girdi kaynağına geçip geri gelin.
- **Öneri listesi hiç çıkmıyor** — Sözlük ilk seferde arka planda bir saniyeden kısa sürede yüklenir; birkaç harf
  daha yazın. Hâlâ çıkmıyorsa **Konsol** uygulamasında `Kurmanci` diye arayıp oradaki mesajları gönderin.
- **Şifre alanlarında çalışmıyor** — Bilerek: macOS şifre alanlarında girdi kaynaklarını kapatır.

---

## Geliştiriciler için

```
mac/
├── Package.swift                  Swift paketi (araçlar 5.9, macOS 13)
├── Sources/KurmanciCore/          Saf Swift, Linux'ta da derlenir ve test edilir
│   ├── KurmanjiDictionary.swift   ← app/…/Dictionary.kt'nin karşılığı
│   ├── Translator.swift           ← app/…/Translator.kt'nin karşılığı
│   ├── UserWords.swift            ← app/…/UserWords.kt (yalnızca seçim sayıları kullanılıyor)
│   └── Composer.swift             Altı çizili metin + öneri listesi mantığı (Mac'e özgü)
├── Sources/KurmanciKlavye/        InputMethodKit uygulaması (yalnızca macOS)
├── Tests/KurmanciCoreTests/       app/src/test'teki JUnit testlerinin XCTest karşılıkları
├── Resources/                     Info.plist, simge, yerelleştirme
├── tools/make_icon.py             Menü simgesini çizer (Pillow)
└── build.sh                       Evrensel (x86_64 + arm64) .app paketini oluşturur
```

- Sözlük kopyalanmaz: `build.sh`, Android'in `app/src/main/assets/tr_ku.tsv` dosyasını pakete koyar. Sözlüğü
  güncellemek için `tools/build_dictionary.py`'yi çalıştırıp Mac sürümünü yeniden derlemek yeterli.
- Kotlin'deki `Dictionary.kt` veya `Translator.kt` (ör. `VERBS` listesi) değişirse Swift karşılığını da aynı
  şekilde güncelleyin; testler iki tarafta aynı.
- Testler (Mac'te tam Xcode gerekir; Linux'ta Swift araç zinciriyle de çalışır):

  ```bash
  swift test
  ```

- Android sürümündeki şu özellikler Mac'te yok: Kurmancî yazma modu ve kelime tamamlama, Kurmancî → Türkçe pano
  çevirisi, "Kelimelerim" düzenleyicisi, emoji paneli ve temalar.

## Lisans

Kod MIT lisanslıdır. Sözlük verisinin çoğu Vikisözlük'ten (Wiktionary, kaikki.org üzerinden) alınmıştır ve
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) lisansı altındadır; bu atıf klavyenin menüsündeki
"Kurmancî Klavye hakkında…" penceresinde de yer alır.
