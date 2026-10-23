/*
Copyright (C) 2026 Q2RTX Android port contributors

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

//
// Runtime Vulkan loading for Android.
//
// The engine doesn't link libvulkan. Instead, volk resolves every Vulkan entry point
// from whichever libvulkan this module opens: the system one, or - when the player
// picked a custom driver (e.g. Mesa Turnip) in the launcher - a private copy of the
// system loader that libadrenotools redirects to that driver.
//

#pragma once

#include <volk.h>
#include <SDL2/SDL_video.h>

// Opens libvulkan and initializes volk. Safe to call more than once.
bool VK_LoadLibrary(void);

// After vkCreateInstance: copies libadrenotools' messages (why a custom driver did or
// didn't load) from logcat into the console log.
void VK_ReportDriverHook(void);

// Human-readable description of the Vulkan driver in use, for logs and error messages.
const char *VK_DriverDescription(void);

// Creates the presentation surface for the SDL window with the loaded Vulkan library.
// (SDL_Vulkan_CreateSurface would go through SDL's own, separately loaded libvulkan,
// which doesn't know about instances created through a custom driver.)
VkResult VK_CreateWindowSurface(SDL_Window *window, VkInstance instance, VkSurfaceKHR *surface);
