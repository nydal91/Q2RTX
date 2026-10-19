/*
Copyright (C) 2026 Q2RTX Android port contributors

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

//
// Touch-screen and game-controller input for the Android port.
//
// Q2RTX on the desktop only knows keyboard and mouse. This module adds:
//  - an on-screen layout: floating move stick on the left half, drag-to-look on
//    the right half, and buttons for fire / jump / crouch / weapons / menu;
//  - SDL GameController support: analog move + look, triggers and buttons mapped
//    to game commands in-game and to menu navigation keys in the UI.
//

#pragma once

#if USE_TOUCH

#include <SDL2/SDL_events.h>

void TOUCH_Init(void);
void TOUCH_Shutdown(void);

// Returns true when the event was used and should not be processed further.
bool TOUCH_HandleEvent(const SDL_Event *event);

// Analog contribution for this frame. forward/side are in [-1, 1];
// yaw/pitch are view angle deltas in degrees.
void TOUCH_GetAnalog(int msec, float *forward, float *side, float *yaw, float *pitch);

// Draws the on-screen controls (call during 2D drawing, in-game only).
void TOUCH_Draw(void);

// True while touch/pad drives the view, so synthesized mouse events must be ignored.
bool TOUCH_InGame(void);

#endif
