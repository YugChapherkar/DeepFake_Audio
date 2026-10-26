package com.phx.yugcall.consent

/**
 * Canonical disclaimer copy. Kept in one place so the first-run screen, the
 * overlay consent screen, and the in-app "About" reference stay in sync
 * rather than drifting into three slightly different claims about what this
 * app can and cannot do.
 */
object Disclaimers {

    val generalPoints = listOf(
        "Not a lie detector or legal proof. VoxShield estimates the likelihood " +
            "that audio contains synthetic or cloned speech. It is a research-" +
            "grade signal, not a verdict — never the sole basis for accusing " +
            "someone, ending a relationship, or making a financial decision.",
        "Independently verify anyone asking for money, credentials, or urgent " +
            "action, regardless of what this app reports. Call the person back " +
            "on a number you already have, ask something only they would know, " +
            "or verify through a second channel.",
        "Errors run in both directions. The bundled model reports well under " +
            "1% error on the lab dataset it was tested on, but real phone audio " +
            "— codecs, background noise, a bad connection — is a different " +
            "situation, and it can misjudge a real voice as synthetic, or the " +
            "reverse.",
        "The score may be uncalibrated. Where the app cannot show a " +
            "trustworthy percentage, it shows \"—\" instead of guessing. Treat " +
            "even a calibrated number as an estimate, not a measurement.",
        "Everything runs on this device. Audio is analysed in memory and " +
            "discarded; nothing is uploaded, and the app has no network access.",
        "This phone cannot check its own live phone call. Android mutes the " +
            "microphone for every app, including this one, for as long as a " +
            "call is active — even on speakerphone — and no permission lifts " +
            "this. To check a caller live, run VoxShield on a second phone " +
            "held near the call's speaker. Recordings and voicemails can be " +
            "checked afterwards with Upload.",
    )

    val overlayPoints = listOf(
        "Recording other people may require their consent, by law. Many " +
            "places require consent from every party on a call before it can " +
            "be recorded or analysed — often called \"two-party\" or " +
            "\"all-party\" consent — and rules vary by state and country. You " +
            "are responsible for knowing and following the law that applies to " +
            "you and the other party before you turn this on.",
        "It cannot hear a call on this same phone. Android mutes every " +
            "third-party app's microphone for the whole of a phone call, even " +
            "on speakerphone. The overlay still shows during calls and tells " +
            "you plainly when Android is muting it. To check a caller live, " +
            "run VoxShield on a second phone near the call's speaker. Video " +
            "and internet calling apps hold the microphone too, and haven't " +
            "been tested.",
        "The floating bubble draws over other apps. That is what lets it stay " +
            "visible during a call, but it is also a permission real security " +
            "tools warn about, because a malicious app could abuse it to " +
            "impersonate other screens. VoxShield uses it only to show its own " +
            "risk indicator and never reads or intercepts taps outside its own " +
            "bubble.",
        "A persistent notification stays visible for as long as this feature " +
            "can access your microphone, so it is never listening without a " +
            "visible indicator. Stop it from the notification or the bubble at " +
            "any time.",
    )
}
