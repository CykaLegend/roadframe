package be.roadframe.coach

enum class ShotGroup(val title: String) {
    ESSENTIAL("The essential five"),
    DETAIL("Details"),
    SCENE("One for the scene"),
    ADVANCED("Advanced")
}

/**
 * Geometry the coach can measure for one shot. Ported from the browser prototype
 * (`reference/shotlist-rt.html`, `SHOTS[].cfg`).
 *
 * @param viewingAngles acceptable viewing angles in degrees: 0 = nose, ±90 = flanks, 180 = tail,
 *   negative = the car's left flank is visible. Empty = the angle is not coached.
 * @param fill target car width over frame width in landscape (portrait scales it by 1.2).
 * @param centerX target centre of the car box, 0..1.
 * @param centerY target centre of the car box, 0..1.
 * @param lowCamera the phone should not point down: coach "LOWER" when pitch says it does.
 * @param leadRoom keep more room in front of the nose than behind the tail.
 * @param angleTolerance degrees of viewing-angle error that still count as correct.
 * @param tracksVehicle false for close-ups: the detector cannot see a whole car, the user drags a box.
 * @param usesLevel false for shots taken from a moving car.
 */
data class ShotGeometry(
    val viewingAngles: List<Float> = emptyList(),
    val fill: Float = 0.72f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val lowCamera: Boolean = false,
    val leadRoom: Boolean = false,
    val angleTolerance: Float = 8f,
    val tracksVehicle: Boolean = true,
    val usesLevel: Boolean = true
)

/** The teaching layer: where to park, where to stand, what to check. */
data class ShotBrief(
    val why: String,
    val park: String,
    val stand: String,
    val distance: String,
    val height: String,
    val lens: String,
    val checklist: List<String>
)

enum class ShotCategory(
    val number: Int,
    val group: ShotGroup,
    val title: String,
    val tag: String,
    /** Zoom ratio the brief recommends; the coach says "ZOOM" before "CLOSER" below it. */
    val recommendedZoom: Float,
    val geometry: ShotGeometry,
    val brief: ShotBrief
) {
    FRONT_THREE_QUARTER(
        1, ShotGroup.ESSENTIAL, "Front three-quarter", "The hero shot", 3f,
        ShotGeometry(viewingAngles = listOf(-45f, 45f), fill = 0.72f, lowCamera = true, leadRoom = true),
        ShotBrief(
            why = "The cover shot. You see the face and the flank at once, so the car reads long, wide and planted.",
            park = "Nose towards you, on a flat clean surface with open space in front of the car. Turn the steering so the front tyres point away from you: that swings the face of the near wheel towards the camera. Check on screen: spokes good, tread bad.",
            stand = "Walk to the front corner, then swing out until you are about 45° off the nose. Back up 8 to 12 m and zoom to 3x. No room? Use 2x and back up as far as you can. Never 0.6x for this one.",
            distance = "8 to 12 m", height = "Headlight height (crouch)", lens = "3x",
            checklist = listOf(
                "Whole car in the frame with a little air around it", "All four wheels visible",
                "Near front wheel shows its face, not its tread", "Ground line level",
                "Nothing growing out of the roof", "You are not reflected in the door",
                "More space in front of the nose than behind the tail"
            )
        )
    ),
    REAR_THREE_QUARTER(
        2, ShotGroup.ESSENTIAL, "Rear three-quarter", "The walking-away shot", 3f,
        ShotGeometry(viewingAngles = listOf(-135f, 135f), fill = 0.72f, lowCamera = true, leadRoom = true),
        ShotBrief(
            why = "The second most-used angle in any feature. Hips, tail lights and exhaust, and on an estate the long roofline finally gets its moment.",
            park = "Back corner towards you. Point the front tyres slightly towards your side so the far front wheel still shows a bit of rim; dead straight is fine if that wheel is hidden anyway.",
            stand = "About 45° off the rear corner, 8 to 12 m back, zoomed to 3x. Same crouch as the hero shot.",
            distance = "8 to 12 m", height = "Tail-light height", lens = "3x",
            checklist = listOf(
                "Whole car in the frame with air around it", "All four wheels visible",
                "Roofline runs clean to the edge of the frame", "Ground line level",
                "Background plain behind the tail", "You are not reflected in the rear quarter panel"
            )
        )
    ),
    SIDE_PROFILE(
        3, ShotGroup.ESSENTIAL, "Side profile", "Pure shape, zero forgiveness", 3f,
        ShotGeometry(viewingAngles = listOf(-90f, 90f), fill = 0.84f, lowCamera = true, angleTolerance = 6f),
        ShotBrief(
            why = "The catalogue shot: the silhouette with nothing to hide behind. Any tilt or distortion shows instantly, which is why it looks so good when it is right.",
            park = "Car exactly parallel to a plain wall or the horizon. Wheels dead straight. Level ground: on a slope the car looks like it is sinking at one end.",
            stand = "Stand square to the middle of the car (the gap between the two doors). Go far, 15 m or more, and zoom to 3x. Distance is what keeps both wheels round instead of egg-shaped.",
            distance = "15 m or more", height = "Door-handle height", lens = "3x",
            checklist = listOf(
                "Roofline and sills parallel to the frame edges", "Both wheels perfectly round",
                "Car centred, equal space left and right", "Plain background: no poles, bins or other cars",
                "Your reflection out of the doors"
            )
        )
    ),
    FRONT(
        4, ShotGroup.ESSENTIAL, "Front straight-on", "The poster", 2f,
        ShotGeometry(viewingAngles = listOf(0f), fill = 0.62f, lowCamera = true, angleTolerance = 6f),
        ShotBrief(
            why = "Symmetry makes a car look aggressive and expensive. This is the one that gets printed big.",
            park = "Nose pointing straight at you, wheels dead straight. A road or lane whose lines run into the distance makes a free frame.",
            stand = "Dead centre of the nose, 6 to 10 m back. Put the badge on the centre vertical grid line and both headlights on the same horizontal line. Get as low as you can.",
            distance = "6 to 10 m", height = "Grille height (sit on the ground)", lens = "2x or 3x",
            checklist = listOf(
                "Badge on the centre line of the grid", "Both headlights level",
                "Both front wheels showing equally", "Mirrors, tyres and lights symmetrical", "Viewpoint really low"
            )
        )
    ),
    REAR(
        5, ShotGroup.ESSENTIAL, "Rear straight-on", "The exhaust and the badge", 2f,
        ShotGeometry(viewingAngles = listOf(180f), fill = 0.62f, lowCamera = true, angleTolerance = 6f),
        ShotBrief(
            why = "Completes the set. Shows the width of the car and the design of the tail lights; on a fast car, the exhausts.",
            park = "Tail pointing straight at you, wheels dead straight, boot fully closed.",
            stand = "Dead centre, 6 to 10 m back, grid on, badge on the centre line. Low again.",
            distance = "6 to 10 m", height = "Tail-light height", lens = "2x or 3x",
            checklist = listOf(
                "Badge on the centre line", "Both tail lights level", "Both rear wheels showing equally",
                "Exhausts inside the frame with room to spare", "Nothing reflected in the rear window but sky"
            )
        )
    ),
    WHEEL(
        6, ShotGroup.DETAIL, "Wheel and brake", "Detail", 2f,
        ShotGeometry(tracksVehicle = false, fill = 0.6f),
        ShotBrief(
            why = "Rims are the most photographed part of any car. Done right, this shot alone sells the whole set.",
            park = "Roll the car until a spoke points straight up and the centre-cap logo reads level. Tyre dressing on, brake dust wiped off the caliper.",
            stand = "Kneel level with the hub, slightly ahead of the wheel, so you see a bit of tyre depth and the caliper through the spokes. Zoom in rather than shoving the phone into the arch.",
            distance = "1.5 m", height = "Hub height (kneel)", lens = "2x or 3x",
            checklist = listOf(
                "Logo level, spoke at 12 o'clock", "Whole wheel in the frame, arch included",
                "Caliper visible through the spokes", "No photographer reflected in the rim", "Tyre sidewall clean"
            )
        )
    ),
    HEADLIGHT(
        7, ShotGroup.DETAIL, "Headlight and badge", "Detail", 3f,
        ShotGeometry(tracksVehicle = false, fill = 0.6f),
        ShotBrief(
            why = "The face of the car, up close. The shot that shows the design work.",
            park = "Any position. Here you move the reflection by moving yourself, not the car.",
            stand = "About 1 m from the lamp, at a slight angle. Watch the reflection of the sky in the lens cover and move until it sits as one clean highlight, not a mess of trees. Rotate the polarizer to taste.",
            distance = "1 m", height = "Headlight height", lens = "3x, close",
            checklist = listOf(
                "Sharp on the lamp detail", "One clean highlight, no clutter in the reflection",
                "A body line leads into or out of the frame", "No fingerprints or dust on the lens"
            )
        )
    ),
    INTERIOR(
        8, ShotGroup.DETAIL, "Interior", "Detail", 1f,
        ShotGeometry(tracksVehicle = false, fill = 0.7f),
        ShotBrief(
            why = "Where the owner actually lives. Editors always ask for one.",
            park = "Straighten the steering wheel, wipe the screen, straighten the mats, and take out everything that is not part of the car.",
            stand = "From outside: door open, phone at seat height, looking across the dash towards the far door. Or from the back seat towards the windscreen. Auto HDR on, because the windows are far brighter than the cabin.",
            distance = "From the open door or the back seat", height = "Seat height", lens = "1x",
            checklist = listOf(
                "Steering wheel straight", "Screen and glass wiped", "Nothing loose anywhere in the cabin",
                "Exposure set for the dash; windows may go bright", "Door pillars vertical, not leaning"
            )
        )
    ),
    SCENE(
        9, ShotGroup.SCENE, "Car in a place", "The wide one", 1f,
        ShotGeometry(viewingAngles = listOf(-45f, 45f), fill = 0.24f, centerX = 0.36f, centerY = 0.58f, angleTolerance = 15f),
        ShotBrief(
            why = "The picture that says where you were. Small car, big scene, and the only shot where the wide lens is welcome.",
            park = "Find the scene first: a road curving away, a big wall, a viewpoint, a harbour. Then park the car small in it, on a rule-of-thirds point, not dead centre. Front tyres turned away from you, like the hero shot.",
            stand = "Back off until the car takes up a quarter of the frame or less. If you use 0.6x, keep the car near the middle: the edges of an ultra-wide stretch everything.",
            distance = "15 to 25 m", height = "Chest height", lens = "1x, or 0.6x with the car near the centre",
            checklist = listOf(
                "Car on a rule-of-thirds point", "Horizon level and not cutting the frame in half",
                "Nothing distracting between you and the car", "Sky and ground both hold detail",
                "The scene is worth the space you gave it"
            )
        )
    ),
    ROLLING(
        10, ShotGroup.ADVANCED, "Rolling shot", "Advanced: needs a second car", 1f,
        ShotGeometry(tracksVehicle = false, usesLevel = false),
        ShotBrief(
            why = "The wheels spin, the road streaks, the car stays sharp. The shot that looks the most like a magazine and takes the most tries.",
            park = "This is a driving shot. You need a second car, a driver for each, a quiet straight road, and matching speed of 30 to 50 km/h. The passenger shoots from the window. The driver only drives.",
            stand = "Camera car alongside or slightly ahead. Brace your elbows on the door frame. Hold the shutter for a burst and keep the car in exactly the same spot on the screen as you drive.",
            distance = "Second car, same speed, 30 to 50 km/h", height = "Passenger window, elbows braced on the door", lens = "1x",
            checklist = listOf(
                "Wheels blurred, body sharp", "Background streaked, not frozen", "Car level in the frame",
                "Whole car in the frame", "Speeds really matched"
            )
        )
    ),
    BLUE_HOUR(
        11, ShotGroup.ADVANCED, "Blue hour, lights on", "Advanced: 20 to 40 minutes after sunset", 3f,
        ShotGeometry(viewingAngles = listOf(-45f, 45f), fill = 0.72f, lowCamera = true, leadRoom = true),
        ShotBrief(
            why = "Deep blue sky, glowing lights, the paint reflecting the last light. The moody closer of the set.",
            park = "Same as the hero shot, but 20 to 40 minutes after sunset. Side lights or daytime running lights on, never full beam. Face the brightest part of the sky.",
            stand = "Same 45° spot, but now the phone must be perfectly still: a mini tripod, a wall, a bin, anything solid. Use the 2-second timer so the tap does not shake it.",
            distance = "8 to 12 m", height = "Headlight height", lens = "3x",
            checklist = listOf(
                "Sky deep blue, not black", "Lights glowing, not blown out", "Car sharp, no shake",
                "Sky reflected on the roof and bonnet", "Wheels turned as in the hero shot"
            )
        )
    );

    val chipLabel: String get() = title.uppercase()

    fun next(): ShotCategory = entries[(ordinal + 1) % entries.size]
}
