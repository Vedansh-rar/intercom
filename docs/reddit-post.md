**Title:** Your pillion has a helmet headset but no intercom? I built a free app that turns two phones into one — no cell signal needed

**Body:**

Most of us have a Bluetooth headset in our helmet for music/calls, but no dedicated intercom to talk to the pillion. Those Cardo/Sena units cost ₹15-30k just so rider and pillion can chat — and even a phone call won't work in the ghats or rural stretches with no reception.

So I built an Android app that connects rider and pillion's phones directly over Bluetooth. Each person routes audio through whatever headset they already have in their helmet. No internet, no cell signal, no subscription — just two phones paired together.

**The problem it solves:**
- Rider and pillion both have Bluetooth headsets in their helmets, but no way to talk to each other
- Dedicated intercoms are expensive for what's basically a short-range voice link
- Phone calls fail in no-reception areas (mountains, forests, highways between towns)
- Wind and engine noise make shouting through the visor useless

**How it works:**
1. Install the app on both phones
2. Pair the phones via Bluetooth (normal phone settings)
3. Rider taps "Host", pillion taps "Join"
4. Talk — audio goes through each person's existing helmet headset

**Features:**
- Direct Bluetooth connection — works anywhere, zero internet needed
- Voice activation — just speak and the channel opens (or push-to-talk for noisy helmets)
- Built-in noise suppression — strips wind and engine rumble
- Works with any Bluetooth headset, wired boom mic, or phone mic
- Stays alive with screen off (foreground service)
- Falls back to Wi-Fi Direct for more range if needed

**What you need:**
- Two Android phones (Android 10+)
- A Bluetooth headset in each helmet (you probably already have this)
- That's it. No intercom hardware, no account, no Wi-Fi.

**Download:** [APK + source code on GitHub](https://github.com/Pavanayi1/intercom)

**Limitations:**
- Half-duplex (walkie-talkie style — one talks, other listens)
- Bluetooth range is ~10-30m (rider to pillion on the same bike is well within this)
- Noise suppression level selector has a bug on some devices — it works but stays on one preset
- Android only for now

For same-bike rider-pillion use, Bluetooth range is more than enough.

**If you ride a KTM or Husqvarna Gen3:** This intercom is also built into [Navigator Gen3](https://github.com/Pavanayi1/KTM-Nav-GEN3) — a free app that mirrors Google Maps turn-by-turn to your bike's dashboard. Your pillion just installs this companion intercom app and you're set. Check out that repo for the full bike control setup.

Free, no ads, open source. If you ride with a pillion and don't want to drop money on a dedicated intercom, give it a try.

Ride safe! 🤙
