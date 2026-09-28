package dev.umb.hostagent.input;

/**
 * LWJGL2 (legacy, 1.7.10) keyboard codes to GLFW (26.2) codes.
 *
 * <p>Both code sets are public, stable specifications: LWJGL2 {@code org.lwjgl.input.Keyboard}
 * constants on one side, GLFW key tokens on the other. Legacy input plans carry LWJGL2 codes
 * (negative codes are mouse buttons, handled separately via {@code InputConstants.Type.MOUSE}
 * and never enter this table). Every code actually used by the shipped plans is covered;
 * unknown codes throw rather than guessing a key.</p>
 */
public final class Lwjgl2ToGlfw {
    // GLFW key tokens (stable public spec).
    public static final int GLFW_SPACE = 32;
    public static final int GLFW_APOSTROPHE = 39;
    public static final int GLFW_COMMA = 44;
    public static final int GLFW_MINUS = 45;
    public static final int GLFW_PERIOD = 46;
    public static final int GLFW_SLASH = 47;
    public static final int GLFW_0 = 48;
    public static final int GLFW_1 = 49;
    public static final int GLFW_2 = 50;
    public static final int GLFW_3 = 51;
    public static final int GLFW_4 = 52;
    public static final int GLFW_5 = 53;
    public static final int GLFW_6 = 54;
    public static final int GLFW_7 = 55;
    public static final int GLFW_8 = 56;
    public static final int GLFW_9 = 57;
    public static final int GLFW_SEMICOLON = 59;
    public static final int GLFW_EQUAL = 61;
    public static final int GLFW_A = 65;
    public static final int GLFW_B = 66;
    public static final int GLFW_C = 67;
    public static final int GLFW_D = 68;
    public static final int GLFW_E = 69;
    public static final int GLFW_F = 70;
    public static final int GLFW_G = 71;
    public static final int GLFW_H = 72;
    public static final int GLFW_I = 73;
    public static final int GLFW_J = 74;
    public static final int GLFW_K = 75;
    public static final int GLFW_L = 76;
    public static final int GLFW_M = 77;
    public static final int GLFW_N = 78;
    public static final int GLFW_O = 79;
    public static final int GLFW_P = 80;
    public static final int GLFW_Q = 81;
    public static final int GLFW_R = 82;
    public static final int GLFW_S = 83;
    public static final int GLFW_T = 84;
    public static final int GLFW_U = 85;
    public static final int GLFW_V = 86;
    public static final int GLFW_W = 87;
    public static final int GLFW_X = 88;
    public static final int GLFW_Y = 89;
    public static final int GLFW_Z = 90;
    public static final int GLFW_LEFT_BRACKET = 91;
    public static final int GLFW_BACKSLASH = 92;
    public static final int GLFW_RIGHT_BRACKET = 93;
    public static final int GLFW_GRAVE_ACCENT = 96;
    public static final int GLFW_ESCAPE = 256;
    public static final int GLFW_ENTER = 257;
    public static final int GLFW_TAB = 258;
    public static final int GLFW_BACKSPACE = 259;
    public static final int GLFW_INSERT = 260;
    public static final int GLFW_DELETE = 261;
    public static final int GLFW_RIGHT = 262;
    public static final int GLFW_LEFT = 263;
    public static final int GLFW_DOWN = 264;
    public static final int GLFW_UP = 265;
    public static final int GLFW_PAGE_UP = 266;
    public static final int GLFW_PAGE_DOWN = 267;
    public static final int GLFW_HOME = 268;
    public static final int GLFW_END = 269;
    public static final int GLFW_CAPS_LOCK = 280;
    public static final int GLFW_SCROLL_LOCK = 281;
    public static final int GLFW_NUM_LOCK = 282;
    public static final int GLFW_F1 = 290;
    public static final int GLFW_F2 = 291;
    public static final int GLFW_F3 = 292;
    public static final int GLFW_F4 = 293;
    public static final int GLFW_F5 = 294;
    public static final int GLFW_F6 = 295;
    public static final int GLFW_F7 = 296;
    public static final int GLFW_F8 = 297;
    public static final int GLFW_F9 = 298;
    public static final int GLFW_F10 = 299;
    public static final int GLFW_F11 = 300;
    public static final int GLFW_F12 = 301;
    public static final int GLFW_KP_0 = 320;
    public static final int GLFW_KP_1 = 321;
    public static final int GLFW_KP_2 = 322;
    public static final int GLFW_KP_3 = 323;
    public static final int GLFW_KP_4 = 324;
    public static final int GLFW_KP_5 = 325;
    public static final int GLFW_KP_6 = 326;
    public static final int GLFW_KP_7 = 327;
    public static final int GLFW_KP_8 = 328;
    public static final int GLFW_KP_9 = 329;
    public static final int GLFW_KP_DECIMAL = 330;
    public static final int GLFW_KP_DIVIDE = 331;
    public static final int GLFW_KP_MULTIPLY = 332;
    public static final int GLFW_KP_SUBTRACT = 333;
    public static final int GLFW_KP_ADD = 334;
    public static final int GLFW_KP_ENTER = 335;
    public static final int GLFW_KP_EQUAL = 336;
    public static final int GLFW_LEFT_SHIFT = 340;
    public static final int GLFW_LEFT_CONTROL = 341;
    public static final int GLFW_LEFT_ALT = 342;
    public static final int GLFW_LEFT_SUPER = 343;
    public static final int GLFW_RIGHT_SHIFT = 344;
    public static final int GLFW_RIGHT_CONTROL = 345;
    public static final int GLFW_RIGHT_ALT = 346;
    public static final int GLFW_RIGHT_SUPER = 347;
    public static final int GLFW_MENU = 348;

    private Lwjgl2ToGlfw() { }

    /** True for legacy mouse codes (button = code + 100); those never enter {@link #keyboardToGlfw}. */
    public static boolean isMouseCode(int lwjglCode) {
        return lwjglCode < 0;
    }

    /** Maps one non-negative LWJGL2 keyboard code to its GLFW token; throws when unknown. */
    public static int keyboardToGlfw(int lwjglCode) {
        switch (lwjglCode) {
            case 1: return GLFW_ESCAPE;
            case 2: return GLFW_1;
            case 3: return GLFW_2;
            case 4: return GLFW_3;
            case 5: return GLFW_4;
            case 6: return GLFW_5;
            case 7: return GLFW_6;
            case 8: return GLFW_7;
            case 9: return GLFW_8;
            case 10: return GLFW_9;
            case 11: return GLFW_0;
            case 12: return GLFW_MINUS;
            case 13: return GLFW_EQUAL;
            case 14: return GLFW_BACKSPACE;
            case 15: return GLFW_TAB;
            case 16: return GLFW_Q;
            case 17: return GLFW_W;
            case 18: return GLFW_E;
            case 19: return GLFW_R;
            case 20: return GLFW_T;
            case 21: return GLFW_Y;
            case 22: return GLFW_U;
            case 23: return GLFW_I;
            case 24: return GLFW_O;
            case 25: return GLFW_P;
            case 26: return GLFW_LEFT_BRACKET;
            case 27: return GLFW_RIGHT_BRACKET;
            case 28: return GLFW_ENTER;
            case 29: return GLFW_LEFT_CONTROL;
            case 30: return GLFW_A;
            case 31: return GLFW_S;
            case 32: return GLFW_D;
            case 33: return GLFW_F;
            case 34: return GLFW_G;
            case 35: return GLFW_H;
            case 36: return GLFW_J;
            case 37: return GLFW_K;
            case 38: return GLFW_L;
            case 39: return GLFW_SEMICOLON;
            case 40: return GLFW_APOSTROPHE;
            case 41: return GLFW_GRAVE_ACCENT;
            case 42: return GLFW_LEFT_SHIFT;
            case 43: return GLFW_BACKSLASH;
            case 44: return GLFW_Z;
            case 45: return GLFW_X;
            case 46: return GLFW_C;
            case 47: return GLFW_V;
            case 48: return GLFW_B;
            case 49: return GLFW_N;
            case 50: return GLFW_M;
            case 51: return GLFW_COMMA;
            case 52: return GLFW_PERIOD;
            case 53: return GLFW_SLASH;
            case 54: return GLFW_RIGHT_SHIFT;
            case 55: return GLFW_KP_MULTIPLY;
            case 56: return GLFW_LEFT_ALT;
            case 57: return GLFW_SPACE;
            case 58: return GLFW_CAPS_LOCK;
            case 59: return GLFW_F1;
            case 60: return GLFW_F2;
            case 61: return GLFW_F3;
            case 62: return GLFW_F4;
            case 63: return GLFW_F5;
            case 64: return GLFW_F6;
            case 65: return GLFW_F7;
            case 66: return GLFW_F8;
            case 67: return GLFW_F9;
            case 68: return GLFW_F10;
            case 69: return GLFW_NUM_LOCK;
            case 70: return GLFW_SCROLL_LOCK;
            case 71: return GLFW_KP_7;
            case 72: return GLFW_KP_8;
            case 73: return GLFW_KP_9;
            case 74: return GLFW_KP_SUBTRACT;
            case 75: return GLFW_KP_4;
            case 76: return GLFW_KP_5;
            case 77: return GLFW_KP_6;
            case 78: return GLFW_KP_ADD;
            case 79: return GLFW_KP_1;
            case 80: return GLFW_KP_2;
            case 81: return GLFW_KP_3;
            case 82: return GLFW_KP_0;
            case 83: return GLFW_KP_DECIMAL;
            case 87: return GLFW_F11;
            case 88: return GLFW_F12;
            case 100: return 302; // GLFW_F13
            case 101: return 303; // GLFW_F14
            case 102: return 304; // GLFW_F15
            case 141: return GLFW_KP_EQUAL;
            case 156: return GLFW_KP_ENTER;
            case 157: return GLFW_RIGHT_CONTROL;
            case 181: return GLFW_KP_DIVIDE;
            case 184: return GLFW_RIGHT_ALT;
            case 199: return GLFW_HOME;
            case 200: return GLFW_UP;
            case 201: return GLFW_PAGE_UP;
            case 203: return GLFW_LEFT;
            case 205: return GLFW_RIGHT;
            case 207: return GLFW_END;
            case 208: return GLFW_DOWN;
            case 209: return GLFW_PAGE_DOWN;
            case 210: return GLFW_INSERT;
            case 211: return GLFW_DELETE;
            case 219: return GLFW_LEFT_SUPER;
            case 220: return GLFW_RIGHT_SUPER;
            case 221: return GLFW_MENU;
            default: throw new IllegalArgumentException("no GLFW mapping for LWJGL2 code " + lwjglCode);
        }
    }
}
