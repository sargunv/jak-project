#pragma once

/*!
 * @file activity.h
 * What the runtime asks of GameActivity, which only Java can do. Call these from a thread that Java
 * can attach, such as the graphics thread, never the EE thread, whose stack is the runtime's own.
 */

// Asks for the display mode that suits the game's frame rate. Only calls Java when the rate
// changes, so call it every frame.
void android_set_frame_rate(float fps);

// The fastest refresh rate the screen supports, or 0 if unknown. The current one can be lower,
// since android_set_frame_rate changes it.
int android_max_refresh_rate();

// Whether the device has a vibrator of its own.
bool android_has_vibrator();

// Vibrates the device at strength, from 0 (stop) to 1, for a time.
void android_vibrate(float strength, int milliseconds);
