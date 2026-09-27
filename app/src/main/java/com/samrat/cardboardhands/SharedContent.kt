package com.samrat.cardboardhands

import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONObject

/**
 * A window watched together in a call ("Смотреть вместе"): the same page is open on both sides, and
 * what either person does to it — touches, typing, back and reload — is done to the other's copy too.
 * Both windows have the same size in pixels, so a touch lands on the same spot.
 */
class SharedContent(val inner: VrWindow.Content) : VrWindow.Content by inner {
    private var lastMove = 0L
    private var lastPointer = 0L

    init {
        (inner as? BrowserContent)?.let { browser ->
            browser.togetherScript = SCRIPT
            // Play, pause and seeking of a video (YouTube and others) go to the other side too.
            browser.onVideo = { state -> Calls.sendInput(JSONObject().put("k", "v").put("s", state)) }
        }
    }

    /** Where our pointer is on the page, for the other side to see (10 times a second at most). */
    fun pointer(u: Float, v: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPointer < 100) return
        lastPointer = now
        Calls.sendInput(JSONObject().put("k", "p").put("u", u.toDouble()).put("v", v.toDouble()))
    }

    override fun touch(action: Int, u: Float, v: Float) {
        inner.touch(action, u, v)
        // Moves at most 20 times a second: the call's channel carries voice and hands too.
        if (action == MotionEvent.ACTION_MOVE) {
            val now = SystemClock.uptimeMillis()
            if (now - lastMove < 50) return
            lastMove = now
        }
        Calls.sendInput(JSONObject().put("k", "t").put("a", action).put("u", u.toDouble()).put("v", v.toDouble()))
    }

    override fun type(key: String) {
        inner.type(key)
        Calls.sendInput(JSONObject().put("k", "y").put("s", key))
    }

    override fun toolbarAction(action: String) {
        inner.toolbarAction(action)
        Calls.sendInput(JSONObject().put("k", "b").put("s", action))
    }

    /** What the other person did, done here without sending it back. */
    fun apply(input: JSONObject) {
        when (input.optString("k")) {
            "t" -> inner.touch(input.optInt("a"), input.optDouble("u").toFloat(), input.optDouble("v").toFloat())
            "y" -> inner.type(input.optString("s"))
            "b" -> inner.toolbarAction(input.optString("s"))
            "p" -> (inner as? BrowserContent)?.runScript("window.__pxrPeer&&__pxrPeer(${input.optDouble("u")},${input.optDouble("v")})")
            "v" -> (inner as? BrowserContent)?.runScript("window.__pxrVideo&&__pxrVideo(${input.optString("s")})")
        }
    }

    private companion object {
        /** The other person's pointer as a blue ring, and video kept in step both ways. */
        const val SCRIPT = """(function(){
if(window.__pxrTogether)return;window.__pxrTogether=true;
var dot=document.createElement('div');
dot.style.cssText='position:fixed;z-index:2147483647;width:28px;height:28px;margin:-14px 0 0 -14px;border-radius:50%;border:4px solid #0A84FF;background:rgba(10,132,255,.25);pointer-events:none;display:none;left:0;top:0';
(document.body||document.documentElement).appendChild(dot);
window.__pxrPeer=function(u,v){dot.style.display='block';dot.style.left=(u*innerWidth)+'px';dot.style.top=(v*innerHeight)+'px';clearTimeout(window.__pxrPeerT);window.__pxrPeerT=setTimeout(function(){dot.style.display='none'},1500)};
var remote=false;
function hook(v){if(v.__pxr)return;v.__pxr=true;['play','pause','seeked'].forEach(function(e){v.addEventListener(e,function(){if(remote)return;PhoneXR.video(JSON.stringify({t:v.currentTime,p:v.paused}))})})}
setInterval(function(){document.querySelectorAll('video').forEach(hook)},1000);
window.__pxrVideo=function(s){var v=document.querySelector('video');if(!v)return;remote=true;if(Math.abs(v.currentTime-s.t)>1)v.currentTime=s.t;if(s.p)v.pause();else v.play();setTimeout(function(){remote=false},600)};
})();"""
    }
}
