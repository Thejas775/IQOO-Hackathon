/* GC9309 panel bring-up and a composited framebuffer.
 *
 * The mascot art, the state labels and the digits are three separate sprite sets that
 * have to land on one screen, so drawing goes through a framebuffer rather than straight
 * to the panel: begin a frame from a mascot pose, blit text onto it, then commit.
 *
 * Commit can animate. The previously presented frame is kept so a transition can slide
 * the old screen out and the new one in without the caller having to hold either.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

/** Panel width in pixels. */
#define DISPLAY_W 240
/** Panel height in pixels. */
#define DISPLAY_H 296

/**
 * Function: display_init
 * Description: Powers the panel rails, initializes SPI and the GC9309, and allocates
 *              the framebuffers. Leaves the backlight off so the first frame can be
 *              composed before anything becomes visible.
 * Parameters: None.
 * Return Value: None.
 */
void display_init(void);

/**
 * Function: display_backlight
 * Description: Turns the panel backlight on or off.
 * Parameters: on - true to light the panel.
 * Return Value: None.
 */
void display_backlight(bool on);

/**
 * Function: display_begin
 * Description: Starts a new frame by filling the framebuffer with a full-screen image.
 * Parameters: background - DISPLAY_W x DISPLAY_H big-endian RGB565 image.
 * Return Value: None.
 */
void display_begin(const uint8_t *background);

/**
 * Function: display_scrim
 * Description: Darkens a horizontal band of the current frame so text drawn over it
 *              stays legible against the mascot art underneath.
 * Parameters: y - Top row of the band; height - Band height in rows;
 *             percent - Brightness to keep, 0 to 100.
 * Return Value: None.
 */
void display_scrim(int y, int height, int percent);

/**
 * Function: display_blit
 * Description: Composites a sprite onto the current frame with a per-channel max.
 * Parameters: sprite - Big-endian RGB565 source; sw, sh - Sprite size; dx, dy - Destination.
 * Return Value: None.
 *
 * Max rather than copy because every sprite is amber on black: taking the brighter of
 * source and destination makes the sprite's black ground vanish into whatever is beneath
 * it, which is the cutout the sprites themselves do not carry.
 */
void display_blit(const uint8_t *sprite, int sw, int sh, int dx, int dy);

/**
 * Function: display_text
 * Description: Draws a string using the pre-rendered glyph sheet. Only '0'-'9' and ':'
 *              exist; any other character advances the cursor and draws nothing.
 * Parameters: text - String to draw; dx, dy - Top-left of the first glyph cell.
 * Return Value: Width in pixels that was drawn.
 */
int display_text(const char *text, int dx, int dy);

/**
 * Function: display_text_centered
 * Description: Draws a string horizontally centred on the panel.
 * Parameters: text - String to draw; dy - Top of the glyph cells.
 * Return Value: None.
 */
void display_text_centered(const char *text, int dy);

/**
 * Function: display_fill
 * Description: Fills the whole frame with one colour, as a background for text.
 * Parameters: r, g, b - Eight-bit channel values.
 * Return Value: None.
 */
void display_fill(uint8_t r, uint8_t g, uint8_t b);

/**
 * Function: display_paragraph
 * Description: Draws word-wrapped proportional text into a box.
 * Parameters: text - UTF-8 string, rendered as ASCII; x, y - Top-left of the box;
 *             width - Box width in pixels; max_rows - Most lines to draw.
 * Return Value: Number of lines drawn.
 *
 * Breaks on spaces, and splits a word that is longer than the box rather than letting
 * it run off the edge. Characters outside the font are drawn as a space, so an em dash
 * or a curly quote from the model leaves a gap rather than corrupting the line.
 */
int display_paragraph(const char *text, int x, int y, int width, int max_rows);

/**
 * Function: display_commit
 * Description: Presents the composed frame.
 * Parameters: animate - true to slide the new frame in from the right, false to cut to it.
 * Return Value: None.
 */
void display_commit(bool animate);
