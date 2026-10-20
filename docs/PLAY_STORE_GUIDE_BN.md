# Pickixo VPN: Play Store-এ প্রকাশের গাইড

ধাপগুলো ক্রমানুসারে করুন। কোনো ধাপ বাদ দিলে অ্যাপ রিজেক্ট হতে পারে।

---

## ধাপ ১: অ্যাপ খুলে ফোনে পরীক্ষা করুন

1. কম্পিউটারে **Android Studio** (সর্বশেষ সংস্করণ) ইনস্টল করুন: https://developer.android.com/studio
2. `File → Open` দিয়ে `PickixoVPN` ফোল্ডারটা খুলুন। প্রথমবার Gradle আর SDK নামাতে ১০–২০ মিনিট লাগতে পারে।
3. ফোনে **Developer options → USB debugging** চালু করে USB দিয়ে কম্পিউটারে লাগান।
4. সবুজ ▶ **Run** বাটনে চাপ দিন।

ডিবাগ বিল্ডে সবসময় Google-এর **টেস্ট বিজ্ঞাপন** দেখায়। নিজের আসল বিজ্ঞাপনে কখনো নিজে ক্লিক করবেন না, এতে AdMob অ্যাকাউন্ট বন্ধ হয়ে যায়।

**পরীক্ষার চেকলিস্ট:**
- [ ] প্রথমবার খুললে "Before you connect" স্ক্রিন আসে। "No thanks" চাপলে সতর্কবার্তা আসে, "I agree" চাপলে মূল স্ক্রিনে যায়।
- [ ] লোকেশন তালিকায় দেশগুলো দেখা যায়।
- [ ] Connect চাপলে সিস্টেম VPN অনুমতি চায়, তারপর "Connected" দেখায় আর স্ট্যাটাস বারে 🔑 আইকন আসে।
- [ ] ব্রাউজারে https://ifconfig.me খুললে অন্য দেশের IP দেখায়।
- [ ] দেশ বদলালে নতুন দেশে কানেক্ট হয়।
- [ ] নোটিফিকেশনের Disconnect বোতাম কাজ করে।
- [ ] Wi-Fi বন্ধ করে মোবাইল ডেটায় গেলে আবার কানেক্ট হয়।

> ⚠️ **গুরুত্বপূর্ণ:** আমি অ্যাপের মূল VPN ইঞ্জিন (SSTP/PPP) একটা টেস্ট সার্ভারের সাথে পুরোপুরি পরীক্ষা করেছি। কিন্তু আমার কাজের পরিবেশ থেকে আসল VPN Gate সার্ভারে পৌঁছানো যায়নি, আর Android বিল্ডও চালানো যায়নি। তাই **ধাপ ১-এর পরীক্ষা আপনার ফোনেই প্রথমবার হবে।** কোনো error এলে Android Studio-র **Logcat**-এ `PickixoVpn` লিখে ফিল্টার করুন আর লেখাটা আমাকে পাঠান। আমি ঠিক করে দেব।

---

## ধাপ ২: AdMob সেটআপ

1. https://admob.google.com-এ আপনার Google অ্যাকাউন্ট দিয়ে সাইনআপ করুন। পেমেন্টের তথ্য পরে দিলেও চলবে।
2. **Apps → Add app → Android**। শুরুতে "app not published yet" বেছে নিন। নাম দিন Pickixo VPN।
3. **App ID** কপি করুন (দেখতে `ca-app-pub-XXXX~YYYY`-এর মতো)।
4. **Ad units → Add ad unit** দিয়ে দুটো ইউনিট বানান:
   - **Banner**: এর ID কপি করুন (`ca-app-pub-XXXX/ZZZZ`)
   - **Interstitial**: এর ID কপি করুন
5. **Privacy & messaging → European regulations (GDPR)** অংশে একটা consent message তৈরি করে Publish করুন। অ্যাপের UMP কোড এটাই দেখাবে।
6. প্রজেক্টের `gradle.properties` ফাইলে ID-গুলো বসান:
   ```
   PICKIXO_ADMOB_APP_ID=ca-app-pub-XXXX~YYYY
   PICKIXO_BANNER_ID=ca-app-pub-XXXX/ZZZZ
   PICKIXO_INTERSTITIAL_ID=ca-app-pub-XXXX/WWWW
   PICKIXO_PRIVACY_URL=https://আপনার-সাইট/privacy-policy.html
   ```
   এগুলো খালি থাকলে রিলিজ বিল্ড ইচ্ছাকৃতভাবে বন্ধ হয়ে যাবে, যাতে ভুল করে টেস্ট বিজ্ঞাপনসহ অ্যাপ প্রকাশ না হয়।
7. অ্যাপ Play Store-এ প্রকাশ হওয়ার পর AdMob-এ অ্যাপটা Play Store লিংকের সাথে যুক্ত (link) করুন, আর `app-ads.txt` সেটআপ করুন।

---

## ধাপ ৩: প্রাইভেসি পলিসি অনলাইনে রাখুন (ফ্রি)

`docs/privacy-policy.html` ফাইলে `YOUR_SUPPORT_EMAIL`-এর জায়গায় আপনার সাপোর্ট ইমেইল বসান।

তারপর ফ্রিতে হোস্ট করার দুটো সহজ উপায়:
- **GitHub Pages:** github.com-এ `pickixo.github.io` নামে একটা পাবলিক রিপো বানান, ফাইলটা `privacy-policy.html` নামে আপলোড করুন, আর Settings → Pages চালু করুন। লিংক হবে `https://pickixo.github.io/privacy-policy.html`।
- **Google Sites:** sites.google.com-এ নতুন সাইট বানিয়ে লেখাটা পেস্ট করুন, তারপর Publish করুন।

এই লিংকটাই `gradle.properties`-এ আর Play Console-এ দেবেন।

---

## ধাপ ৪: সাইন করা রিলিজ ফাইল (AAB) বানান

1. Android Studio-তে **Build → Generate Signed App Bundle / APK → Android App Bundle**।
2. **Create new…** দিয়ে keystore বানান।
   - ⚠️ এই `.jks` ফাইল আর এর পাসওয়ার্ড **হারালে অ্যাপ আর কখনো আপডেট করা যাবে না।** Google Drive আর পেনড্রাইভ, দুই জায়গায় ব্যাকআপ রাখুন।
3. **release** বেছে নিয়ে Finish চাপুন। ফাইল তৈরি হবে `app/release/app-release.aab`-এ।
4. প্রতিটি নতুন আপডেটের আগে `app/build.gradle.kts`-এ `versionCode` এক বাড়ান (1 → 2 → 3…)।

---

## ধাপ ৫: Play Console অ্যাকাউন্ট

1. https://play.google.com/console-এ যান। এককালীন ফি $25, কার্ড লাগবে।
2. **Personal** অ্যাকাউন্ট হলে জাতীয় পরিচয়পত্র দিয়ে পরিচয় যাচাই করতে হবে।
3. ⚠️ **নতুন personal অ্যাকাউন্টের নিয়ম:** Production-এ প্রকাশের আগে একটা **Closed testing**-এ কমপক্ষে **১২ জন টেস্টারকে টানা ১৪ দিন** অপ্ট-ইন রাখতে হবে। ১৪ দিনের মধ্যে কেউ বের হয়ে গেলে তাকে গণনা করা হয় না।
   - বন্ধু ও পরিবারের Gmail ঠিকানা আগে থেকে জোগাড় করে রাখুন, আর তাদের অ্যাপটা মাঝে মাঝে ব্যবহার করতে বলুন।

---

## ধাপ ৬: অ্যাপ তৈরি আর Store listing

**Create app** অংশে:
- নাম: Pickixo VPN
- ধরন: App
- মূল্য: Free
- Default language: English

**Store listing:** সব লেখা আর ছবি তৈরি করা আছে। কোন ছবি কোথায় যাবে আর কোন লেখা কোন ঘরে পেস্ট করবেন, সব আছে **`docs/ASO_STORE_LISTING.md`**-এ:
- App icon: `store/icon-512.png`
- Feature graphic: `store/feature-graphic-en.png` (বাংলা listing-এ `feature-graphic-bn.png`)
- Screenshots: `store/screenshots/en/` আর `store/screenshots/bn/`, প্রতিটিতে ৬টি
- App name, short আর full description: ইংরেজি ও বাংলা দুটোই
- Category: **Tools**

---

## ধাপ ৭: App content (Policy → App content)

### Privacy policy
ধাপ ৩-এর লিংকটা দিন।

### Ads
**Yes, my app contains ads**

### App access
**All functionality is available without special access.** অ্যাপে কোনো লগইন নেই।

### Target audience
**18 and over** বেছে নিন। VPN অ্যাপ শিশুদের জন্য নয়, আর কম বয়সী বেছে নিলে Families নীতিমালার কঠিন নিয়ম লাগু হয়।

### Content rating
প্রশ্নাবলি সৎভাবে পূরণ করুন। এটা Utility বা Tools ধরনের অ্যাপ।

### Data safety
নিচের উত্তরগুলো Google-এর AdMob ডকুমেন্টেশন অনুযায়ী দেওয়া। জমা দেওয়ার আগে Play Console-এর সর্বশেষ প্রশ্নের সাথে মিলিয়ে নিন।

| প্রশ্ন | উত্তর |
|---|---|
| Does your app collect or share user data? | **Yes** |
| Is all data encrypted in transit? | **Yes** |
| Can users request deletion? | অ্যাপে কোনো অ্যাকাউন্ট নেই। ফোনের ডেটা আনইনস্টল করলেই মুছে যায় |

| ডেটার ধরন (AdMob SDK) | Collected | Shared | উদ্দেশ্য |
|---|---|---|---|
| Location → Approximate location (IP থেকে) | ✅ | ✅ | Advertising, Analytics, Fraud prevention |
| App activity → App interactions | ✅ | ✅ | Advertising, Analytics |
| App info and performance → Diagnostics | ✅ | ✅ | Analytics, Fraud prevention |
| Device or other IDs | ✅ | ✅ | Advertising, Analytics, Fraud prevention |
| **Web browsing history** (VPN Gate-এর লগ) | ✅ | ✅ | App functionality |

শেষ সারির ব্যাখ্যা: Pickixo নিজে ব্রাউজিং তথ্য রাখে না, কিন্তু VPN Gate রাখে, আর ট্রাফিক আপনার অ্যাপের মাধ্যমেই তাদের কাছে যায়। এটা **ঘোষণা করে দেওয়াই নিরাপদ।** কম ঘোষণা করলে অ্যাপ সাসপেন্ড হতে পারে, বেশি ঘোষণা করলে কোনো ক্ষতি নেই।

### VpnService declaration (সবচেয়ে গুরুত্বপূর্ণ)
**Policy → App content → VpnService**-এ যান:

| প্রশ্ন | উত্তর |
|---|---|
| Is VPN the core functionality of your app? | **Yes** |
| What data is collected/shared via VpnService? | ইংরেজিতে লিখুন: "Pickixo does not collect or store user traffic. The VPN tunnels device traffic to third-party VPN Gate volunteer servers chosen by the user. VPN Gate logs connection metadata (user IP, time, destination hosts/IPs/ports) for at least 3 months, as disclosed in-app before first use and in the privacy policy." |
| Does the app redirect or manipulate other apps' traffic for monetization? | **No.** অ্যাপ নিজের ট্রাফিককে VPN-এর বাইরে রাখে (`addDisallowedApplication`), আর অন্য অ্যাপের বিজ্ঞাপন বা ট্রাফিক বদলায় না |
| Encryption | ডিভাইস থেকে সার্ভার পর্যন্ত SSTP over TLS |

**দুটো ভিডিও লাগবে, প্রতিটি ৯০ সেকেন্ডের কম।** ফোনের স্ক্রিন রেকর্ডার দিয়ে বানিয়ে YouTube-এ **Unlisted** করে আপলোড করুন, তারপর লিংক দিন।

**ভিডিও ১: VPN-এর ব্যবহার (~৬০ সেকেন্ড)**
1. অ্যাপ খুলুন, মূল স্ক্রিন দেখান।
2. লোকেশন কার্ডে চাপ দিয়ে দেশের তালিকা দেখান, একটা দেশ বেছে নিন।
3. Connect চাপুন। সিস্টেমের VPN অনুমতি আসলে OK চাপুন, তারপর "Connected" আর 🔑 আইকন দেখান।
4. ব্রাউজারে ifconfig.me খুলে নতুন দেশের IP দেখান।
5. অ্যাপে ফিরে Disconnect চাপুন।

**ভিডিও ২: সতর্কবার্তা ও সম্মতি (~৪৫ সেকেন্ড)**
1. ফোনের Settings → Apps → Pickixo VPN → **Clear storage** করুন, যাতে প্রথমবারের মতো খোলে।
2. অ্যাপ খুলুন, "Before you connect" স্ক্রিন পুরোটা ধীরে স্ক্রল করে দেখান।
3. **No thanks** চাপুন, সতর্কবার্তা দেখান, তারপর **Review again** চাপুন। এটা "রাজি না হওয়ার পথ"।
4. **I agree** চাপুন, মূল স্ক্রিন আসবে। এটা "রাজি হওয়ার পথ"।

---

## ধাপ ৮: টেস্টিং থেকে প্রকাশ

1. **Testing → Closed testing → Create track**। AAB আপলোড করুন, টেস্টারদের ইমেইল যোগ করুন, আর অপ্ট-ইন লিংক তাদের পাঠান।
2. ১৪ দিন পর **Dashboard → Apply for production**।
3. **Production → Create new release**: AAB দিন, তারপর Review। প্রথম রিভিউতে সাধারণত কয়েক দিন থেকে এক সপ্তাহ লাগে, VPN অ্যাপে কখনো বেশি।

---

## ধাপ ৯: VPN Gate-কে ইমেইল (লঞ্চের আগে)

`docs/email-to-vpngate.txt`-এ ইমেইলের খসড়া আছে। আপনার নাম আর ইমেইল বসিয়ে vpngate.net-এ দেওয়া যোগাযোগের ঠিকানায় পাঠান। তারা কোনো শর্ত দিলে মেনে চলুন। না বললে প্রকাশ করবেন না।

---

## রিজেক্ট হলে সাধারণ কারণ ও সমাধান

| কারণ | সমাধান |
|---|---|
| "Prominent disclosure" ভিডিও অসম্পূর্ণ | ভিডিও ২-এ রাজি হওয়া আর না-হওয়া, দুই পথই দেখান |
| Data safety ভুল | ওপরের টেবিল অনুযায়ী সব ডেটার ধরন ঘোষণা করুন |
| Store listing-এ VpnService উল্লেখ নেই | Full description-এর শেষ লাইনটা রাখুন |
| বিজ্ঞাপন নীতিমালা | ইন্টারস্টিশিয়াল বিজ্ঞাপন শুধু Connect-এর পর আসে, ৩ মিনিটে একবারের বেশি নয়। এটা বাড়াবেন না |
| Misleading claims | "100% secure", "anonymous", "unlimited fast" জাতীয় দাবি লিখবেন না |
