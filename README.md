# J.A.R.V.I.S. v2.0 for Android

Dimaag: Google Gemini. Haath: Accessibility Service. Aankhein: Notification access.

## Build (PC par, ek baar)
1. Android Studio mein `File > Open` se ye folder kholein (jis mein `settings.gradle.kts` hai). Gradle sync hone dein.
2. `secrets.properties` mein Gemini key pehle se daali hui hai. Nayi key ho to wahan `GEMINI_KEY=` badal dein, ya app ke Setup screen mein paste karein.
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)` -> `app/build/outputs/apk/debug/app-debug.apk`
4. APK phone mein install karein.

## Phone par setup
App kholein -> oopar "SETUP" -> cards ko ek ek karke green karein:
1. Mic/Calls/SMS/Contacts  2. Restricted settings (Android 13+)  3. Accessibility  4. Notification access  5. All files access  6. Gemini key + naam  7. WhatsApp auto-reply

## Kaise use karein
- Home screen par bade reactor ko dabayein aur bolein. Ya kisi bhi app ke upar tairta orb dabayein. Ya Quick Settings mein "JARVIS" tile add karein.
- Neeche likh kar bhi command de sakte hain.

## Misaalein
- "WhatsApp kholo aur Ali ko likho main aa raha hoon"
- "Jab bhi koi msg aaye to khud reply kar dena" (auto-reply ON)
- "Sirf Ammi aur Ali ko auto reply karna" / "Auto reply band karo"
- "Downloads mein resume wali file kholo"
- "Volume 40 karo", "torch on", "gaana next", "7 baje ka alarm", "screenshot lo"
- "Kisi ka message aya?" (recent notifications parhta hai)

## Hadood (sach)
- Lock screen / fingerprint nahi khol sakta. Banking apps accessibility block karti hain.
- "Hey Jarvis" wake word abhi nahi: Android background mein mic ko is tarah chalne nahi deta. Orb / tile / app se shuru karein.
- Auto-reply sirf wahi WhatsApp messages dekh sakta hai jin ki notification aayi ho (chat khuli ho ya notification mute ho to nahi).
- Free Gemini ki limit hai; limit par doosra model try hota hai.
