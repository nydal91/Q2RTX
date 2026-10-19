/*
Copyright (C) 2026 Q2RTX Android port contributors

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
*/

//
// touch.c -- on-screen touch controls and game controller support
//

#include "../client/client.h"
#include "touch.h"

#include <SDL2/SDL.h>

#if USE_TOUCH

#define MAX_FINGERS     10
#define HOLD_ID_TOUCH   1000    // key ids passed to +cmd/-cmd, unique per source
#define HOLD_ID_PAD     2000

static cvar_t *touch_enable;
static cvar_t *touch_sens;
static cvar_t *touch_alpha;
static cvar_t *touch_stick_size;
static cvar_t *joy_sens;
static cvar_t *joy_invert;
static cvar_t *joy_deadzone;

/*
===============================================================================

LAYOUT

Button centers are given as fractions of the screen (x of width, y of height),
sizes as fractions of the screen height so buttons stay round on any aspect.

===============================================================================
*/

typedef enum {
    ACT_HOLD,       // "+cmd" on press, "-cmd" on release
    ACT_COMMAND,    // command executed once on press
    ACT_ESCAPE,     // behaves like the Escape key (opens the menu)
} action_t;

typedef struct {
    const char  *label;
    float       x, y, size;
    action_t    action;
    const char  *cmd;
    bool        steers;     // dragging this finger also turns the view
} tbutton_t;

static const tbutton_t tbuttons[] = {
    { "FIRE", 0.865f, 0.64f, 0.20f,  ACT_HOLD,    "attack",   true  },
    { "JUMP", 0.945f, 0.38f, 0.125f, ACT_HOLD,    "moveup",   false },
    { "DUCK", 0.945f, 0.88f, 0.125f, ACT_HOLD,    "movedown", false },
    { "WPN>", 0.715f, 0.88f, 0.11f,  ACT_COMMAND, "weapnext", false },
    { "<WPN", 0.600f, 0.88f, 0.11f,  ACT_COMMAND, "weapprev", false },
    { "USE",  0.745f, 0.38f, 0.10f,  ACT_COMMAND, "invuse",   false },
    { "INV",  0.640f, 0.38f, 0.10f,  ACT_COMMAND, "inven",    false },
    { "MENU", 0.045f, 0.08f, 0.10f,  ACT_ESCAPE,  NULL,       false },
    { "CON",  0.110f, 0.08f, 0.10f,  ACT_COMMAND, "toggleconsole", false },
};

#define NUM_TBUTTONS    q_countof(tbuttons)

typedef enum {
    ROLE_NONE,
    ROLE_MOVE,
    ROLE_LOOK,
    ROLE_BUTTON,
} role_t;

typedef struct {
    bool            active;
    SDL_FingerID    id;
    role_t          role;
    int             button;
    float           x, y;           // last position, pixels
    float           ox, oy;         // stick origin, pixels
} finger_t;

static struct {
    finger_t    fingers[MAX_FINGERS];
    bool        button_down[NUM_TBUTTONS];

    float       move_x, move_y;     // stick deflection, [-1, 1]
    float       look_dx, look_dy;   // accumulated drag in pixels

    unsigned    last_touch;         // time of the last finger event
    bool        was_in_game;

    SDL_GameController  *pad;
    SDL_JoystickID      pad_id;
    bool        pad_hold[SDL_CONTROLLER_BUTTON_MAX];
    bool        trigger_down[2];
} touch;

static int screen_w(void) { return r_config.width  > 0 ? r_config.width  : 1; }
static int screen_h(void) { return r_config.height > 0 ? r_config.height : 1; }

bool TOUCH_InGame(void)
{
    return cls.state == ca_active && cls.key_dest == KEY_GAME;
}

static void send_hold(const char *cmd, int id, bool down)
{
    Cbuf_AddText(&cmd_buffer, va("%c%s %d %u\n", down ? '+' : '-', cmd, id, com_eventTime));
}

static void send_command(const char *cmd)
{
    Cbuf_AddText(&cmd_buffer, va("%s\n", cmd));
}

static void press_escape(void)
{
    unsigned now = Sys_Milliseconds();
    Key_Event(K_ESCAPE, true, now);
    Key_Event(K_ESCAPE, false, now);
}

/*
===============================================================================

TOUCH

===============================================================================
*/

static float stick_radius(void)
{
    return Cvar_ClampValue(touch_stick_size, 0.05f, 0.4f) * screen_h();
}

static void button_center(const tbutton_t *b, float *x, float *y, float *r)
{
    *x = b->x * screen_w();
    *y = b->y * screen_h();
    *r = b->size * screen_h() * 0.5f;
}

static int button_at(float x, float y)
{
    for (int i = 0; i < NUM_TBUTTONS; i++) {
        float bx, by, br;
        button_center(&tbuttons[i], &bx, &by, &br);
        br *= 1.15f;    // be a little generous with fat fingers
        if ((x - bx) * (x - bx) + (y - by) * (y - by) <= br * br)
            return i;
    }
    return -1;
}

static void button_press(int i, bool down)
{
    const tbutton_t *b = &tbuttons[i];

    if (touch.button_down[i] == down)
        return;
    touch.button_down[i] = down;

    switch (b->action) {
    case ACT_HOLD:
        send_hold(b->cmd, HOLD_ID_TOUCH + i, down);
        break;
    case ACT_COMMAND:
        if (down)
            send_command(b->cmd);
        break;
    case ACT_ESCAPE:
        if (down)
            press_escape();
        break;
    }
}

static void update_stick(finger_t *f)
{
    float r = stick_radius();
    float dx = f->x - f->ox;
    float dy = f->y - f->oy;
    float len = sqrtf(dx * dx + dy * dy);

    // floating stick: drag the origin along when the finger goes past the rim
    if (len > r) {
        f->ox = f->x - dx * r / len;
        f->oy = f->y - dy * r / len;
        dx = f->x - f->ox;
        dy = f->y - f->oy;
    }

    touch.move_x = dx / r;
    touch.move_y = dy / r;
}

static finger_t *find_finger(SDL_FingerID id)
{
    for (int i = 0; i < MAX_FINGERS; i++)
        if (touch.fingers[i].active && touch.fingers[i].id == id)
            return &touch.fingers[i];
    return NULL;
}

static void release_finger(finger_t *f)
{
    if (f->role == ROLE_MOVE)
        touch.move_x = touch.move_y = 0;
    else if (f->role == ROLE_BUTTON && f->button >= 0)
        button_press(f->button, false);
    memset(f, 0, sizeof(*f));
}

static void release_all(void)
{
    for (int i = 0; i < MAX_FINGERS; i++)
        if (touch.fingers[i].active)
            release_finger(&touch.fingers[i]);

    for (int i = 0; i < NUM_TBUTTONS; i++)
        button_press(i, false);

    touch.move_x = touch.move_y = 0;
    touch.look_dx = touch.look_dy = 0;

    // gamepad holds are released through their own commands
    for (int b = 0; b < SDL_CONTROLLER_BUTTON_MAX; b++)
        touch.pad_hold[b] = false;
    for (int t = 0; t < 2; t++)
        touch.trigger_down[t] = false;
    Cbuf_AddText(&cmd_buffer, "-attack\n-moveup\n-movedown\n");
}

// Show the on-screen keyboard while the console is open, so commands can be typed.
// SDL turns the typed text into key events, which the console already handles.
static void update_text_input(void)
{
    bool want = (cls.key_dest & KEY_CONSOLE) != 0;
    if (want != (bool)SDL_IsTextInputActive()) {
        if (want)
            SDL_StartTextInput();
        else
            SDL_StopTextInput();
    }
}

static void check_game_state(void)
{
    update_text_input();

    bool in_game = TOUCH_InGame();
    if (touch.was_in_game && !in_game)
        release_all();
    touch.was_in_game = in_game;
}

static bool finger_event(const SDL_TouchFingerEvent *ev)
{
    float x = ev->x * screen_w();
    float y = ev->y * screen_h();
    finger_t *f;

    if (!touch_enable->integer)
        return false;

    touch.last_touch = Sys_Milliseconds();

    if (!TOUCH_InGame()) {
        // menus use the mouse events SDL synthesizes from touches
        return false;
    }

    switch (ev->type) {
    case SDL_FINGERDOWN:
        if (find_finger(ev->fingerId))
            return true;
        for (f = touch.fingers; f < touch.fingers + MAX_FINGERS; f++)
            if (!f->active)
                break;
        if (f == touch.fingers + MAX_FINGERS)
            return true;

        memset(f, 0, sizeof(*f));
        f->active = true;
        f->id = ev->fingerId;
        f->x = f->ox = x;
        f->y = f->oy = y;
        f->button = button_at(x, y);

        if (f->button >= 0) {
            f->role = ROLE_BUTTON;
            button_press(f->button, true);
        } else if (x < screen_w() * 0.45f) {
            // only one move stick at a time; extra fingers on the left just look
            bool have_stick = false;
            for (int i = 0; i < MAX_FINGERS; i++)
                if (touch.fingers[i].active && touch.fingers[i].role == ROLE_MOVE)
                    have_stick = true;
            f->role = have_stick ? ROLE_LOOK : ROLE_MOVE;
        } else {
            f->role = ROLE_LOOK;
        }
        return true;

    case SDL_FINGERMOTION:
        f = find_finger(ev->fingerId);
        if (!f)
            return true;
        if (f->role == ROLE_LOOK || (f->role == ROLE_BUTTON && f->button >= 0 && tbuttons[f->button].steers)) {
            touch.look_dx += x - f->x;
            touch.look_dy += y - f->y;
        }
        f->x = x;
        f->y = y;
        if (f->role == ROLE_MOVE)
            update_stick(f);
        return true;

    case SDL_FINGERUP:
        f = find_finger(ev->fingerId);
        if (f)
            release_finger(f);
        return true;
    }

    return false;
}

/*
===============================================================================

GAME CONTROLLER

===============================================================================
*/

static void open_pad(int device_index)
{
    if (touch.pad || !SDL_IsGameController(device_index))
        return;

    touch.pad = SDL_GameControllerOpen(device_index);
    if (touch.pad) {
        touch.pad_id = SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(touch.pad));
        Com_Printf("Game controller connected: %s\n", SDL_GameControllerName(touch.pad));
    }
}

static void close_pad(SDL_JoystickID id)
{
    if (touch.pad && touch.pad_id == id) {
        Com_Printf("Game controller disconnected\n");
        SDL_GameControllerClose(touch.pad);
        touch.pad = NULL;
        release_all();
    }
}

// What a controller button does while playing.
static void pad_game_button(int button, bool down)
{
    const char *hold = NULL, *cmd = NULL;

    switch (button) {
    case SDL_CONTROLLER_BUTTON_A:               hold = "moveup";   break;
    case SDL_CONTROLLER_BUTTON_B:               hold = "movedown"; break;
    case SDL_CONTROLLER_BUTTON_X:               cmd = "invuse";    break;
    case SDL_CONTROLLER_BUTTON_Y:               cmd = "weapnext";  break;
    case SDL_CONTROLLER_BUTTON_LEFTSHOULDER:    cmd = "weapprev";  break;
    case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER:   cmd = "weapnext";  break;
    case SDL_CONTROLLER_BUTTON_DPAD_UP:         cmd = "weapnext";  break;
    case SDL_CONTROLLER_BUTTON_DPAD_DOWN:       cmd = "weapprev";  break;
    case SDL_CONTROLLER_BUTTON_DPAD_LEFT:       cmd = "invprev";   break;
    case SDL_CONTROLLER_BUTTON_DPAD_RIGHT:      cmd = "invnext";   break;
    case SDL_CONTROLLER_BUTTON_BACK:            cmd = "inven";     break;
    case SDL_CONTROLLER_BUTTON_RIGHTSTICK:      cmd = "centerview"; break;
    case SDL_CONTROLLER_BUTTON_LEFTSTICK:       cmd = "score";     break;
    case SDL_CONTROLLER_BUTTON_START:
        if (down)
            press_escape();
        return;
    default:
        return;
    }

    if (hold) {
        if (touch.pad_hold[button] != down) {
            touch.pad_hold[button] = down;
            send_hold(hold, HOLD_ID_PAD + button, down);
        }
    } else if (cmd && down) {
        send_command(cmd);
    }
}

// What a controller button does in menus and the console.
static void pad_menu_button(int button, bool down)
{
    unsigned key;

    switch (button) {
    case SDL_CONTROLLER_BUTTON_A:           key = K_ENTER;      break;
    case SDL_CONTROLLER_BUTTON_B:
    case SDL_CONTROLLER_BUTTON_BACK:
    case SDL_CONTROLLER_BUTTON_START:       key = K_ESCAPE;     break;
    case SDL_CONTROLLER_BUTTON_DPAD_UP:     key = K_UPARROW;    break;
    case SDL_CONTROLLER_BUTTON_DPAD_DOWN:   key = K_DOWNARROW;  break;
    case SDL_CONTROLLER_BUTTON_DPAD_LEFT:   key = K_LEFTARROW;  break;
    case SDL_CONTROLLER_BUTTON_DPAD_RIGHT:  key = K_RIGHTARROW; break;
    default:
        return;
    }

    Key_Event(key, down, Sys_Milliseconds());
}

static void pad_trigger(int which, float value)
{
    static const char *const cmds[2] = { "moveup", "attack" };  // left, right
    bool down = touch.trigger_down[which] ? value > 0.3f : value > 0.5f;

    if (down == touch.trigger_down[which])
        return;
    touch.trigger_down[which] = down;

    if (TOUCH_InGame() || !down)
        send_hold(cmds[which], HOLD_ID_PAD + SDL_CONTROLLER_BUTTON_MAX + which, down);
    else if (which == 1)
        Key_Event(K_ENTER, true, Sys_Milliseconds()), Key_Event(K_ENTER, false, Sys_Milliseconds());
}

static float pad_axis(SDL_GameControllerAxis axis)
{
    float dead = Cvar_ClampValue(joy_deadzone, 0, 0.9f);
    float v = SDL_GameControllerGetAxis(touch.pad, axis) / 32767.0f;
    float a = fabsf(v);

    if (a < dead)
        return 0;
    // rescale so motion starts smoothly at the edge of the dead zone
    a = Q_clipf((a - dead) / (1 - dead), 0, 1);
    return v < 0 ? -a : a;
}

/*
===============================================================================

PUBLIC

===============================================================================
*/

bool TOUCH_HandleEvent(const SDL_Event *ev)
{
    if (!touch_enable)
        return false;

    check_game_state();

    switch (ev->type) {
    case SDL_FINGERDOWN:
    case SDL_FINGERUP:
    case SDL_FINGERMOTION:
        return finger_event(&ev->tfinger);

    case SDL_CONTROLLERDEVICEADDED:
        open_pad(ev->cdevice.which);
        return true;

    case SDL_CONTROLLERDEVICEREMOVED:
        close_pad(ev->cdevice.which);
        return true;

    case SDL_CONTROLLERBUTTONDOWN:
    case SDL_CONTROLLERBUTTONUP:
        if (!touch.pad || ev->cbutton.which != touch.pad_id)
            return true;
        if (TOUCH_InGame())
            pad_game_button(ev->cbutton.button, ev->type == SDL_CONTROLLERBUTTONDOWN);
        else if (ev->type == SDL_CONTROLLERBUTTONUP && touch.pad_hold[ev->cbutton.button])
            pad_game_button(ev->cbutton.button, false);     // finish a hold started in-game
        else
            pad_menu_button(ev->cbutton.button, ev->type == SDL_CONTROLLERBUTTONDOWN);
        return true;

    case SDL_CONTROLLERAXISMOTION:
        if (!touch.pad || ev->caxis.which != touch.pad_id)
            return true;
        if (ev->caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERLEFT)
            pad_trigger(0, ev->caxis.value / 32767.0f);
        else if (ev->caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERRIGHT)
            pad_trigger(1, ev->caxis.value / 32767.0f);
        return true;
    }

    return false;
}

void TOUCH_GetAnalog(int msec, float *forward, float *side, float *yaw, float *pitch)
{
    float h = screen_h();

    *forward = *side = *yaw = *pitch = 0;

    if (!touch_sens)
        return;     // video (and with it TOUCH_Init) not up yet

    check_game_state();
    if (!TOUCH_InGame()) {
        touch.look_dx = touch.look_dy = 0;
        return;
    }

    // touch: stick moves, drags turn. A full-height drag turns ~120 degrees at sens 1.
    *forward -= touch.move_y;
    *side    += touch.move_x;

    float deg_per_px = 120.0f / h * touch_sens->value;
    *yaw   -= touch.look_dx * deg_per_px;
    *pitch += touch.look_dy * deg_per_px;
    touch.look_dx = touch.look_dy = 0;

    // controller: left stick moves, right stick turns at up to 200 deg/s at sens 1
    if (touch.pad) {
        float lx = pad_axis(SDL_CONTROLLER_AXIS_LEFTX);
        float ly = pad_axis(SDL_CONTROLLER_AXIS_LEFTY);
        float rx = pad_axis(SDL_CONTROLLER_AXIS_RIGHTX);
        float ry = pad_axis(SDL_CONTROLLER_AXIS_RIGHTY);
        float rate = 200.0f * joy_sens->value * msec * 0.001f;

        *forward -= ly;
        *side    += lx;
        // squared response gives finer aim near the center
        *yaw   -= rx * fabsf(rx) * rate;
        *pitch += ry * fabsf(ry) * rate * (joy_invert->integer ? -1.f : 1.f);
    }

    *forward = Q_clipf(*forward, -1, 1);
    *side    = Q_clipf(*side, -1, 1);
}

// Filled square with a 2px border, centered at (x, y), in the current scaled space.
static void draw_box(int x, int y, int half, uint32_t fill, uint32_t border)
{
    R_DrawFill32(x - half, y - half, half * 2, half * 2, fill);
    R_DrawFill32(x - half, y - half, half * 2, 2, border);
    R_DrawFill32(x - half, y + half - 2, half * 2, 2, border);
    R_DrawFill32(x - half, y - half, 2, half * 2, border);
    R_DrawFill32(x + half - 2, y - half, 2, half * 2, border);
}

void TOUCH_Draw(void)
{
    if (!touch_enable || !touch_enable->integer || !TOUCH_InGame())
        return;

    // with a controller in use, only show the overlay after the screen is touched
    if (touch.pad && (!touch.last_touch || Sys_Milliseconds() - touch.last_touch > 10000))
        return;

    // draw in a virtual space ~540 px tall so labels stay readable on any phone
    float scale = 540.0f / screen_h();
    float a = Cvar_ClampValue(touch_alpha, 0, 1);
    uint8_t fa = (uint8_t)(a * 120), ba = (uint8_t)(a * 255);

    R_SetScale(scale);

    for (int i = 0; i < NUM_TBUTTONS; i++) {
        float x, y, r;
        button_center(&tbuttons[i], &x, &y, &r);
        bool down = touch.button_down[i];
        draw_box(Q_rint(x * scale), Q_rint(y * scale), Q_rint(r * scale * 0.85f),
                 down ? MakeColor(118, 185, 0, fa * 2) : MakeColor(0, 0, 0, fa),
                 MakeColor(118, 185, 0, ba));
        R_SetColor(MakeColor(255, 255, 255, ba));
        SCR_DrawStringEx(Q_rint(x * scale), Q_rint(y * scale) - CHAR_HEIGHT / 2, UI_CENTER,
                         MAX_STRING_CHARS, tbuttons[i].label, SCR_GetFont());
    }

    // movement stick: base ring and knob where the finger is
    for (int i = 0; i < MAX_FINGERS; i++) {
        finger_t *f = &touch.fingers[i];
        if (!f->active || f->role != ROLE_MOVE)
            continue;
        float r = stick_radius();
        draw_box(Q_rint(f->ox * scale), Q_rint(f->oy * scale), Q_rint(r * scale),
                 MakeColor(0, 0, 0, fa / 2), MakeColor(255, 255, 255, ba / 2));
        draw_box(Q_rint(f->x * scale), Q_rint(f->y * scale), Q_rint(r * scale * 0.35f),
                 MakeColor(118, 185, 0, fa * 2), MakeColor(255, 255, 255, ba));
    }

    R_ClearColor();
    R_SetScale(1.0f);
}

void TOUCH_Init(void)
{
    touch_enable     = Cvar_Get("touch_enable", "1", CVAR_ARCHIVE);
    touch_sens       = Cvar_Get("touch_sens", "1", CVAR_ARCHIVE);
    touch_alpha      = Cvar_Get("touch_alpha", "0.5", CVAR_ARCHIVE);
    touch_stick_size = Cvar_Get("touch_stick_size", "0.14", CVAR_ARCHIVE);
    joy_sens         = Cvar_Get("joy_sens", "1", CVAR_ARCHIVE);
    joy_invert       = Cvar_Get("joy_invert", "0", CVAR_ARCHIVE);
    joy_deadzone     = Cvar_Get("joy_deadzone", "0.15", CVAR_ARCHIVE);

    if (!SDL_WasInit(SDL_INIT_GAMECONTROLLER) &&
        SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) < 0) {
        Com_WPrintf("Couldn't initialize SDL game controllers: %s\n", SDL_GetError());
        return;
    }

    for (int i = 0; i < SDL_NumJoysticks(); i++)
        open_pad(i);
}

void TOUCH_Shutdown(void)
{
    release_all();
    if (touch.pad)
        SDL_GameControllerClose(touch.pad);
    memset(&touch, 0, sizeof(touch));
}

#endif // USE_TOUCH
