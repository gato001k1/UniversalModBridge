package dev.umb.hostagent.content;

/**
 * GLFW (26.2) to LWJGL-2 (legacy {@code GuiScreen.keyTyped(char, keyCode)}) key translation.
 * Legacy text widgets decide by key code for editing keys (backspace 14, arrows, home/end,
 * delete) and by char for printable input and Ctrl shortcuts (1 = select all, 3 = copy,
 * 22 = paste, 24 = cut), so both halves of the pair are produced here.
 */
final class LegacyKeyCodes {
    private LegacyKeyCodes() {
    }

    private static final int[] LETTERS = {
            30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, // A..M
            49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44  // N..Z
    };

    /** LWJGL-2 key code for a GLFW key, or 0 when legacy has no equivalent. */
    static int lwjgl(int glfw) {
        if (glfw >= 65 && glfw <= 90) return LETTERS[glfw - 65];
        if (glfw >= 49 && glfw <= 57) return glfw - 49 + 2; // 1..9
        switch (glfw) {
            case 48: return 11;   // 0
            case 32: return 57;   // space
            case 39: return 40;   // apostrophe
            case 44: return 51;   // comma
            case 45: return 12;   // minus
            case 46: return 52;   // period
            case 47: return 53;   // slash
            case 59: return 39;   // semicolon
            case 61: return 13;   // equal
            case 91: return 26;   // left bracket
            case 92: return 43;   // backslash
            case 93: return 27;   // right bracket
            case 96: return 41;   // grave
            case 256: return 1;   // escape
            case 257: return 28;  // enter
            case 258: return 15;  // tab
            case 259: return 14;  // backspace
            case 260: return 210; // insert
            case 261: return 211; // delete
            case 262: return 205; // right
            case 263: return 203; // left
            case 264: return 208; // down
            case 265: return 200; // up
            case 266: return 201; // page up
            case 267: return 209; // page down
            case 268: return 199; // home
            case 269: return 207; // end
            case 335: return 156; // keypad enter
            default: return 0;
        }
    }

    /** LWJGL-2 key code legacy would report alongside a typed char (0 when none). */
    static int lwjglForChar(char c) {
        if (c >= 'a' && c <= 'z') return LETTERS[c - 'a'];
        if (c >= 'A' && c <= 'Z') return LETTERS[c - 'A'];
        if (c >= '1' && c <= '9') return c - '1' + 2;
        switch (c) {
            case '0': return 11;
            case ' ': return 57;
            case '-': case '_': return 12;
            case '=': case '+': return 13;
            case '.': case '>': return 52;
            case ',': case '<': return 51;
            case '/': case '?': return 53;
            case ';': case ':': return 39;
            case '\'': case '"': return 40;
            case '[': case '{': return 26;
            case ']': case '}': return 27;
            case '\\': case '|': return 43;
            case '`': case '~': return 41;
            default: return 0;
        }
    }

    /** Keys legacy text widgets handle by code (no charTyped is generated for them). */
    static boolean isEditingKey(int glfw) {
        switch (glfw) {
            case 257: case 258: case 259: case 261: case 262: case 263: case 264: case 265:
            case 268: case 269: case 335:
                return true;
            default:
                return false;
        }
    }

    /** The char LWJGL-2 paired with an editing key. */
    static char editingChar(int glfw) {
        switch (glfw) {
            case 257: case 335: return '\r';
            case 258: return '\t';
            case 259: return '\b';
            default: return '\0';
        }
    }

    /** Control char LWJGL-2 reported for Ctrl+A/C/V/X (0 for other keys). */
    static char ctrlChar(int glfw) {
        switch (glfw) {
            case 65: return (char) 1;
            case 67: return (char) 3;
            case 86: return (char) 22;
            case 88: return (char) 24;
            default: return '\0';
        }
    }
}
