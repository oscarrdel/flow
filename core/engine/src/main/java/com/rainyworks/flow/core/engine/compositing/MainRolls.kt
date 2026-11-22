package com.rainyworks.flow.core.engine.compositing

import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.Track

/**
 * The main track split into A/B rolls: even clips on [a], odd clips on [b]. Within each roll
 * clips never overlap, so each can be a regular Media3 sequence; [windows] are the times
 * where the compositor blends one roll into the other.
 */
class MainRolls(val a: Track, val b: Track, val windows: List<TransitionWindow>) {
    companion object {
        /** Returns null when no clip overlaps its predecessor, so the plain single-track path is used. */
        fun split(main: Track): MainRolls? {
            val clips = main.clips
            val windows = clips.withIndex().drop(1).mapNotNull { (i, clip) ->
                val overlap = Edits.transitionOverlap(clips[i - 1], clip)
                if (overlap <= 0L) return@mapNotNull null
                TransitionWindow(clip.startUs, clip.startUs + overlap, clip.transitionIn!!.type, fromA = i % 2 == 1)
            }
            if (windows.isEmpty()) return null
            val a = main.copy(id = "${main.id}-a", clips = clips.filterIndexed { i, _ -> i % 2 == 0 })
            val b = main.copy(id = "${main.id}-b", clips = clips.filterIndexed { i, _ -> i % 2 == 1 })
            return MainRolls(a, b, windows)
        }
    }
}
