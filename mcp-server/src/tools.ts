/**
 * The full mcpfabric tool catalogue.
 *
 * This table is the single source of truth for the RPC contract on the TypeScript side: every
 * entry maps an MCP tool (snake_case name) to a bridge RPC `method` (namespaced, dotted) plus a
 * zod input schema. `src/index.ts` registers each entry generically. Keep this in sync with the
 * Java handler registry in the mod (`dev.mcpfabric.handlers.*`).
 */
import { z } from "zod";

export interface ToolDef {
  /** MCP tool name exposed to the model. */
  name: string;
  /** Bridge RPC method this tool forwards to. */
  method: string;
  /** Short human title. */
  title: string;
  /** Description shown to the model — be precise about behaviour and side requirements. */
  description: string;
  /** zod raw shape describing the tool arguments. */
  inputSchema: z.ZodRawShape;
  /** Hints surfaced to MCP clients. */
  annotations?: {
    readOnlyHint?: boolean;
    destructiveHint?: boolean;
    idempotentHint?: boolean;
    openWorldHint?: boolean;
  };
  /** Rendering: "json" (default) returns text + structuredContent; "image" returns an image block. */
  kind?: "json" | "image";
}

// ----- reusable schema fragments -------------------------------------------------------------

const vec3 = () => ({
  x: z.number().describe("X coordinate (east/west)."),
  y: z.number().describe("Y coordinate (height)."),
  z: z.number().describe("Z coordinate (north/south)."),
});

/**
 * The caller's shorthand for "that thing", as an alternative to coordinates or a UUID. Resolved on the
 * mod side against the world at the moment the step runs — which is what lets a plan end with "walk to
 * the nearest drop" before that drop exists.
 */
const TARGET_SPEC =
  '"nearest_hostile" (closest hostile mob), "nearest_drop" (closest dropped item), ' +
  '"nearest_animal", "looking_at" (whatever the crosshair is on), ' +
  '"visible_log" / "visible_stone" / "visible:<block id>" (nearest block of that kind the eye can ' +
  'actually see from where the player is looking — turn the camera first if it is not in view)';

/**
 * The condition vocabulary `rules_set` accepts — mirrors Rules.CONDITIONS on the mod side, so a rule
 * reads like the observation it is watching.
 */
const RULES_CONDITIONS =
  "dead, healthBelow, healthAbove, airBelow, foodBelow, underwater, inWater, inLava, onFire, onGround, " +
  "fallingHard, hostileWithin (blocks), hostilesAtLeast, dropsAtLeast, itemCount:{itemId:count}, " +
  "holding, visible:{id, atLeast, within}";

/** x/y/z or 'target', for the tools that accept either. */
const goalArgs = () => ({
  x: z.number().optional().describe("X coordinate. Give x/y/z, or 'target' instead."),
  y: z.number().optional().describe("Y coordinate. Give x/y/z, or 'target' instead."),
  z: z.number().optional().describe("Z coordinate. Give x/y/z, or 'target' instead."),
  target: z.string().optional().describe(`What to aim at, instead of x/y/z: ${TARGET_SPEC}.`),
});

const dimensionOpt = {
  dimension: z
    .string()
    .optional()
    .describe('Dimension id, e.g. "minecraft:overworld", "minecraft:the_nether". Defaults to the current/overworld dimension.'),
};

const READ = { readOnlyHint: true } as const;
const WRITE = { destructiveHint: true } as const;

/**
 * Was "how long this call may block for". Nothing blocks any more, so there is nothing to cap — the
 * parameter is still accepted so an existing caller does not fail validation, but the mod ignores it
 * and says so out loud, because a caller who believes it is waiting will wait for something that
 * already returned.
 */
const waitSeconds = (_maxTimeout: number) =>
  z
    .number()
    .optional()
    .describe(
      "DEPRECATED AND IGNORED. No action blocks any more: every call returns the moment it is submitted. " +
        "Watch what it is doing with action_status / observe instead of waiting here.",
    );

/**
 * How this mod is meant to be driven, stated once so every action tool can lean on it. There are no
 * composite behaviours on the mod side — no "chop this tree", no "fight this mob until it dies", no
 * "gather everything nearby". Each action is one small physical step, and the caller decides the
 * sequence: observe, act, observe, act.
 */
const COMPOSE_NOTE =
  " MODULES ARE COMPOSED BY YOU, NOT BY THE MOD: there is no 'chop a tree', 'kill that mob' or " +
  "'pick up the loot' behaviour to call. Each action does one step and returns, and you chain them — " +
  "dig per log (move_to first if the trunk is a different level, and re-scan with vision.scan after each " +
  "one — felling a trunk exposes the next, and dig answers 'blocked' with blockedBy when a leaf is in the " +
  "way), then move_to onto the drops so the player picks them up, then craft and click_slot. Same for a " +
  "fight: retrieve the mob, move_to into range while it is still where you last saw it, attack, then " +
  "re-observe and recompose. Keep each call short, " +
  "read the world between steps, and change your plan when it no longer matches. When you already know " +
  "the sequence, chain the steps in ONE run_plan call instead of one round-trip each; call them " +
  "separately when you need to look at the world between the steps. " +
  "Work in small batches, not long lists: a handful of steps, then look at what actually happened. " +
  "And for anything routine — surfacing when the air runs low, disengaging at low health — write it once " +
  "with rules_set rather than watching for it on every read: those rules are yours, they run every tick, " +
  "and they are reported in the stream when they fire, so the reflex costs you no round trip at all.";

/** The live-watch payload attached to every action result. */
const OBSERVE_NOTE =
  ' NOTHING HERE BLOCKS: the call returns as soon as the action is in charge, and the world keeps ticking ' +
  'underneath it, so you always keep the last word. Every result carries an "observe" snapshot sampled every tick ' +
  'while it ran: vitals, nearby hostiles, drops on the ground, what the crosshair is on, the action\'s own ' +
  '"progress", and a rolling "log" of notable moments — so the world is visible during the action instead of only ' +
  'after it. Watch with action_status / observe and read observe.danger (0 fine / 1 caution / 2 act now). To change ' +
  'course you do not need permission or a cancel first: the next action you send supersedes whatever is running, ' +
  'and action_cancel stops it outright and reports it as cancelled.';

// ----- catalogue ------------------------------------------------------------------------------

// Shared bits for the cheat group (TEST ONLY — see McpConfig.enableCheats).
const CHEAT = { destructiveHint: true } as const;
const CHEAT_ITEM = z.string().describe('Item or block id, e.g. "diamond_pickaxe" or "minecraft:stone".');
const CHEAT_NOTE =
  " TEST-ONLY cheat: requires enableCheats in the mod config and operator rights in the world.";

export const TOOLS: ToolDef[] = [
  // ===== info ================================================================================
  {
    name: "get_status",
    method: "info.status",
    title: "Server/client status",
    description:
      "Get the current state of the running game: mod & Minecraft version, whether the player is in a world, and the list of available capability groups. The mod is client-side only, so it works on any server without operator rights. Call this first to learn what you can do right now.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "list_capabilities",
    method: "info.capabilities",
    title: "List capabilities",
    description:
      "List every capability group and whether it is currently available on this side (e.g. control/interact/vision/nav are client-only; players/command admin need a server). Useful to decide which tools will work.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== world (read) ========================================================================
  {
    name: "get_block",
    method: "world.getBlock",
    title: "Get block at position",
    description:
      "Read the block at an exact integer position: its block id, blockstate properties, whether it is air/fluid/solid, light levels, and hardness. " +
      "Read from the client's own loaded view, so it needs no server access; positions outside loaded chunks are reported as unavailable rather than guessed.",
    inputSchema: { ...vec3(), ...dimensionOpt },
    annotations: READ,
  },
  {
    name: "get_blocks_region",
    method: "world.getBlocks",
    title: "Scan a cuboid region",
    description:
      "Scan all blocks in the cuboid between two corners (inclusive) and return their ids. Volume is capped (default 32768 blocks) to protect the server; air is omitted unless includeAir is true. Use for mapping a small area.",
    inputSchema: {
      from: z.object(vec3()).describe("One corner of the cuboid."),
      to: z.object(vec3()).describe("Opposite corner of the cuboid."),
      includeAir: z.boolean().optional().default(false).describe("Include air blocks in the result."),
      maxBlocks: z.number().int().min(1).max(200000).optional().describe("Override the per-call block cap."),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "find_blocks",
    method: "world.findBlocks",
    title: "Find nearby blocks by id",
    description:
      "Search a spherical radius around a center point for blocks matching any of the given ids (e.g. minecraft:diamond_ore). Returns matches sorted by distance. Only searches loaded chunks.",
    inputSchema: {
      center: z.object(vec3()).describe("Center of the search sphere."),
      radius: z.number().int().min(1).max(128).describe("Search radius in blocks."),
      blockIds: z.array(z.string()).min(1).describe('Block ids to match, e.g. ["minecraft:diamond_ore","minecraft:ancient_debris"].'),
      maxResults: z.number().int().min(1).max(1024).optional().default(64).describe("Maximum matches to return."),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "get_time_and_weather",
    method: "world.getTimeAndWeather",
    title: "Time & weather",
    description:
      "Get the current day-time (0-24000), total game-time, day count, and weather (raining/thundering) for a dimension.",
    inputSchema: { ...dimensionOpt },
    annotations: READ,
  },
  {
    name: "list_dimensions",
    method: "world.getDimensions",
    title: "Current dimension",
    description:
      "Report the dimension the player is currently in. Only the current dimension is known: a client has no access to dimensions it is not inside.",
    inputSchema: {},
    annotations: READ,
  },
  // ===== entities ============================================================================
  {
    name: "query_entities",
    method: "entities.query",
    title: "Query entities",
    description:
      "List entities, optionally filtered by a sphere (center+radius), entity type ids, living-only, and whether to include players. Returns position, type, name, health and key flags for each.",
    inputSchema: {
      center: z.object(vec3()).optional().describe("Center of the search sphere; omit to use the player's position."),
      radius: z.number().min(1).max(256).optional().default(32).describe("Search radius in blocks."),
      types: z.array(z.string()).optional().describe('Entity type ids to match, e.g. ["minecraft:zombie","minecraft:cow"].'),
      includePlayers: z.boolean().optional().default(true),
      onlyLiving: z.boolean().optional().default(false),
      maxResults: z.number().int().min(1).max(1000).optional().default(100),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "get_entity",
    method: "entities.get",
    title: "Get entity details",
    description: "Get detailed info about a single entity by UUID: type, position, velocity, health, equipment, NBT-derived attributes.",
    inputSchema: { uuid: z.string().describe("Entity UUID.") },
    annotations: READ,
  },
  // ===== chat ================================================================================
  {
    name: "send_chat",
    method: "chat.send",
    title: "Send chat message",
    description:
      "Send a chat message. On a client this is sent as the local player (a leading '/' runs a command as that player); on a dedicated server it is broadcast.",
    inputSchema: { message: z.string() },
  },
  {
    name: "get_recent_chat",
    method: "chat.getRecent",
    title: "Get recent chat",
    description: "Return recently observed chat & system messages (most recent last).",
    inputSchema: { limit: z.number().int().min(1).max(500).optional().default(50) },
    annotations: READ,
  },

  // ===== cheats (TEST ONLY) ===================================================================
// Every one of these just types a vanilla command as the local player, so nothing here bypasses a
// permission, a gamerule or a server-side check: in a world where the player is not an operator they
// fail exactly like typing the command would. The whole group is refused unless "enableCheats" is set
// in config/mcpfabric.config.json. Meant for standing up a scenario quickly instead of grinding.
...[
  {
    name: "cheat_command",
    method: "cheat.command",
    title: "Run a command (cheat)",
    description:
      "TEST-ONLY. Run any vanilla command as the local player (leading '/' optional). The generic " +
      "escape hatch behind the other cheat_* helpers — use it for anything not wrapped, e.g. " +
      '"gamerule doDaylightCycle false" or "loot give @s loot minecraft:chests/simple_dungeon".' +
      CHEAT_NOTE,
    inputSchema: { command: z.string().describe("The command, with or without a leading '/'.") },
    annotations: CHEAT,
  },
  {
    name: "cheat_give",
    method: "cheat.give",
    title: "Give items (cheat)",
    description: "TEST-ONLY. Put items straight into the player's inventory." + CHEAT_NOTE,
    inputSchema: {
      item: CHEAT_ITEM,
      count: z.number().int().min(1).max(6400).optional().default(1),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_summon",
    method: "cheat.summon",
    title: "Summon an entity (cheat)",
    description:
      "TEST-ONLY. Spawn an entity — the quickest way to have the mob you want to train against. " +
      "Coordinates are optional (defaults to the player's position)." + CHEAT_NOTE,
    inputSchema: {
      type: z.string().describe('Entity type, e.g. "minecraft:zombie".'),
      x: z.number().optional(),
      y: z.number().optional(),
      z: z.number().optional(),
      nbt: z.string().optional().describe("Optional NBT tag, e.g. '{NoAI:1b}'."),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_set_time",
    method: "cheat.set_time",
    title: "Set the time (cheat)",
    description: "TEST-ONLY. Jump to a time of day — handy for testing night behaviour." + CHEAT_NOTE,
    inputSchema: {
      time: z.string().describe('"day", "noon", "night", "midnight", or a tick value like "18000".'),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_set_weather",
    method: "cheat.set_weather",
    title: "Set the weather (cheat)",
    description: "TEST-ONLY. Force clear/rain/thunder, optionally for a limited time." + CHEAT_NOTE,
    inputSchema: {
      weather: z.enum(["clear", "rain", "thunder"]),
      durationSeconds: z.number().int().min(1).max(1000000).optional(),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_set_game_mode",
    method: "cheat.set_game_mode",
    title: "Set the game mode (cheat)",
    description:
      "TEST-ONLY. Switch game mode — creative is the fastest way to move the player somewhere or to " +
      "inspect a build without falling." + CHEAT_NOTE,
    inputSchema: { mode: z.enum(["survival", "creative", "adventure", "spectator"]) },
    annotations: CHEAT,
  },
  {
    name: "cheat_teleport",
    method: "cheat.teleport",
    title: "Teleport the player (cheat)",
    description:
      "TEST-ONLY. Move the player to a coordinate instantly, without walking there first." + CHEAT_NOTE,
    inputSchema: {
      ...vec3(),
      yaw: z.number().optional(),
      pitch: z.number().optional(),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_effect",
    method: "cheat.effect",
    title: "Apply a status effect (cheat)",
    description:
      "TEST-ONLY. Give the player a potion effect, e.g. water_breathing to work on underwater " +
      "behaviour or night_vision to see." + CHEAT_NOTE,
    inputSchema: {
      effect: z.string().describe('Effect id, e.g. "minecraft:water_breathing".'),
      seconds: z.number().int().min(1).max(1000000).optional().default(30),
      amplifier: z.number().int().min(0).max(255).optional().default(0),
      hideParticles: z.boolean().optional().default(false),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_enchant",
    method: "cheat.enchant",
    title: "Enchant the held item (cheat)",
    description: "TEST-ONLY. Enchant whatever the player is holding." + CHEAT_NOTE,
    inputSchema: {
      enchantment: z.string().describe('Enchantment id, e.g. "minecraft:efficiency".'),
      level: z.number().int().min(1).max(255).optional().default(1),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_xp",
    method: "cheat.xp",
    title: "Give experience (cheat)",
    description: "TEST-ONLY. Add experience points (or levels)." + CHEAT_NOTE,
    inputSchema: {
      amount: z.number().int().min(0).max(1000000),
      levels: z.boolean().optional().default(false),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_damage",
    method: "cheat.damage",
    title: "Damage the player (cheat)",
    description:
      "TEST-ONLY. Hurt the player by a fixed amount — the direct way to test low-health, retreat " +
      "and death handling without hunting for a mob." + CHEAT_NOTE,
    inputSchema: {
      amount: z.number().min(0).max(1000),
      type: z.string().optional().describe('Damage type, e.g. "minecraft:drowning".'),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_kill",
    method: "cheat.kill",
    title: "Kill nearby entities (cheat)",
    description:
      "TEST-ONLY. Clear entities around the player — useful for ending a fight or tidying up test " +
      "dummies. Never affects players." + CHEAT_NOTE,
    inputSchema: {
      radius: z.number().min(1).max(128).optional().default(8),
      type: z.string().optional().describe("Restrict to one entity type."),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_clear",
    method: "cheat.clear",
    title: "Clear inventory (cheat)",
    description: "TEST-ONLY. Empty the inventory, or just one item." + CHEAT_NOTE,
    inputSchema: { item: CHEAT_ITEM.optional() },
    annotations: CHEAT,
  },
  {
    name: "cheat_set_block",
    method: "cheat.set_block",
    title: "Set a block (cheat)",
    description: "TEST-ONLY. Place a single block — build a step, a wall or a target to mine." + CHEAT_NOTE,
    inputSchema: {
      ...vec3(),
      block: CHEAT_ITEM,
      mode: z.enum(["replace", "destroy", "keep"]).optional(),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_fill",
    method: "cheat.fill",
    title: "Fill a region (cheat)",
    description:
      "TEST-ONLY. Fill a box of blocks — the fast way to build a test rig (a pool, a pillar, a wall)." +
      CHEAT_NOTE,
    inputSchema: {
      from: z.object({ x: z.number(), y: z.number(), z: z.number() }),
      to: z.object({ x: z.number(), y: z.number(), z: z.number() }),
      block: CHEAT_ITEM,
      mode: z.enum(["replace", "destroy", "hollow", "keep", "outline"]).optional(),
    },
    annotations: CHEAT,
  },
  {
    name: "cheat_open_to_lan",
    method: "cheat.open_to_lan",
    title: "Enable cheats in a single-player world (cheat)",
    description:
      "TEST-ONLY. Open the current single-player world to LAN with cheats on, which is what grants " +
      "the host operator rights — the one thing the rest of the cheat_* group needs and cannot grant " +
      "itself. Refuses on a real server. Note it genuinely opens a LAN port for the world, so close " +
      "it again when you are done testing." + CHEAT_NOTE,
    inputSchema: {
      port: z.number().int().min(0).max(65535).optional().default(0).describe("0 lets the game pick."),
    },
    annotations: CHEAT,
  },
],

  {
    name: "get_self",
    method: "player.getState",
    title: "Get local player state",
    description:
      "Client-only. Full state of YOUR player: position, yaw/pitch, motion, health, food, saturation, air, XP, game mode, on-ground, in-fluid, selected hotbar slot, dimension.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_inventory",
    method: "player.getInventory",
    title: "Get inventory",
    description: "Client-only. Full inventory: main slots, hotbar, armor, offhand, and the selected slot. Each item reports id, count and durability.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_equipment",
    method: "player.getEquipment",
    title: "Get equipment",
    description: "Client-only. Currently equipped items: main hand, off hand, helmet, chestplate, leggings, boots.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_status_effects",
    method: "player.getStatusEffects",
    title: "Get active effects",
    description: "Client-only. Active status effects on your player with amplifier and remaining duration.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== control (client) ====================================================================
  {
    name: "set_movement",
    method: "control.setInput",
    title: "Set movement input",
    description:
      "Client-only. Set held movement inputs as booleans; they persist until changed (like holding keys). Any omitted field is left unchanged. Combine with look/look_at to walk somewhere. Use stop_movement to release everything.",
    inputSchema: {
      forward: z.boolean().optional(),
      back: z.boolean().optional(),
      left: z.boolean().optional(),
      right: z.boolean().optional(),
      jump: z.boolean().optional(),
      sneak: z.boolean().optional(),
      sprint: z.boolean().optional(),
    },
  },
  {
    name: "stop_movement",
    method: "control.stop",
    title: "Release all movement",
    description: "Client-only. Release all movement inputs (stop walking/jumping/sneaking/sprinting).",
    inputSchema: {},
  },
  {
    name: "look",
    method: "control.look",
    title: "Set/adjust look angles",
    description:
      "Client-only. Set absolute yaw/pitch, or apply relative deltas. Yaw: 0=south,-90=east,90=west,180=north. Pitch: -90=up, 90=down. The turn is SMOOTH by default (interpolated over ticks like moving a mouse); pass instant:true to snap.",
    inputSchema: {
      yaw: z.number().optional().describe("Absolute yaw in degrees."),
      pitch: z.number().optional().describe("Absolute pitch in degrees (-90..90)."),
      deltaYaw: z.number().optional().describe("Relative yaw change in degrees."),
      deltaPitch: z.number().optional().describe("Relative pitch change in degrees."),
      instant: z.boolean().optional().default(false).describe("Snap instantly instead of turning smoothly."),
    },
  },
  {
    name: "look_at",
    method: "control.lookAt",
    title: "Look at a point",
    description:
      "Client-only. Turn the player to face a world coordinate. Smooth by default (interpolated over ticks); pass instant:true to snap.",
    inputSchema: {
      ...vec3(),
      instant: z.boolean().optional().default(false).describe("Snap instantly instead of turning smoothly."),
    },
  },
  {
    name: "jump",
    method: "control.jumpOnce",
    title: "Jump once",
    description: "Client-only. Perform a single jump.",
    inputSchema: {},
  },
  {
    name: "respawn",
    method: "control.respawn",
    title: "Respawn after death",
    description: "Client-only. Activate the death screen's Respawn button (respawn at world spawn/bed).",
    inputSchema: {},
    annotations: WRITE,
  },
  {
    name: "start_using_item",
    method: "control.startUsing",
    title: "Start using held item",
    description: "Client-only. Begin using/holding the right-click action of the held item (eat, draw bow, block with shield, etc.).",
    inputSchema: {},
  },
  {
    name: "stop_using_item",
    method: "control.stopUsing",
    title: "Stop using held item",
    description: "Client-only. Release the right-click use action.",
    inputSchema: {},
  },

  // ===== interact (client) ===================================================================
  {
    name: "place_block",
    method: "interact.placeBlock",
    title: "Place held block",
    description:
      "Client-only. Place the currently held block against the given position/face (must be reachable). Equip the desired block first with select_hotbar_slot.",
    inputSchema: { ...vec3(), face: z.enum(["up", "down", "north", "south", "east", "west"]).optional().default("up") },
    annotations: WRITE,
  },
  {
    name: "use_item",
    method: "interact.useItem",
    title: "Use item / right-click",
    description: "Client-only. Perform a right-click use with the held item on whatever is under the crosshair (or in air).",
    inputSchema: {},
  },
  {
    name: "attack_entity",
    method: "interact.attackEntity",
    title: "Attack entity",
    description: "Client-only. Attack (left-click) an entity by UUID. Must be in reach.",
    inputSchema: { uuid: z.string() },
    annotations: WRITE,
  },
  {
    name: "use_entity",
    method: "interact.useEntity",
    title: "Interact with entity",
    description: "Client-only. Right-click/interact with an entity by UUID (e.g. trade with a villager, mount a horse).",
    inputSchema: { uuid: z.string() },
  },
  {
    name: "drop_held_item",
    method: "interact.dropItem",
    title: "Drop held item",
    description: "Client-only. Drop the held item (one, or the whole stack).",
    inputSchema: { wholeStack: z.boolean().optional().default(false) },
  },

  // ===== inventory (client) ==================================================================
  {
    name: "select_hotbar_slot",
    method: "inventory.selectHotbar",
    title: "Select hotbar slot",
    description: "Client-only. Select a hotbar slot (0-8) as the held item.",
    inputSchema: { slot: z.number().int().min(0).max(8) },
  },
  {
    name: "drop_slot",
    method: "inventory.dropSlot",
    title: "Drop a specific slot",
    description: "Client-only. Drop the contents of a specific inventory slot.",
    inputSchema: { slot: z.number().int().min(0).max(40).describe("Inventory slot index (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)."), wholeStack: z.boolean().optional().default(true) },
  },
  {
    name: "swap_slots",
    method: "inventory.swapSlots",
    title: "Swap two inventory slots",
    description: "Client-only. Swap the items in two inventory slots via container clicks (player inventory must be the active screen-less context).",
    inputSchema: { slotA: z.number().int().min(0).max(45), slotB: z.number().int().min(0).max(45) },
  },
  {
    name: "craft_item",
    method: "inventory.craft",
    title: "Craft an item with a real grid",
    description:
      "Client-only. Craft the way a player does: it opens the container screen (the inventory, like pressing E) and clicks the grid one slot per tick, so the crafting is visible in-game and blocks until it finishes — the recipe match, ingredient consumption and result count all come from the game (nothing is spawned). " +
      "Provide 'grid': a row-major array of item ids (or null) whose length selects the grid — 4 for the player's 2x2 grid (opened automatically) or 9 for a placed crafting table (3x3; right-click the table first so its menu is open). " +
      "'count' caps how many times to craft (limited by available materials). Returns the crafted amount and the output item.",
    inputSchema: {
      grid: z
        .array(z.string().nullable())
        .min(4)
        .max(9)
        .describe('Row-major grid contents: 4 entries for the player 2x2 grid, 9 for a crafting table 3x3. Use null for empty slots, e.g. ["minecraft:oak_log", null, null, null].'),
      count: z.number().int().min(1).max(64).optional().default(1).describe("How many times to craft (capped by available materials)."),
    },
    annotations: WRITE,
  },

  // ===== vision (client) =====================================================================
  {
    name: "screenshot",
    method: "vision.screenshot",
    title: "Capture screenshot",
    description:
      "Client-only. Capture the current game framebuffer as a PNG image (at the game's current resolution) so a vision-capable model can literally see what the player sees.",
    inputSchema: {},
    annotations: READ,
    kind: "image",
  },
  {
    name: "describe_scene",
    method: "vision.describeScene",
    title: "Describe visible scene",
    description:
      "Client-only. Produce a structured, text description of what is visible: the block/entity directly under the crosshair, a grid of raycasts across the field of view, and nearby visible entities. A cheap alternative to a screenshot for non-vision models.",
    inputSchema: {
      maxDistance: z.number().min(1).max(128).optional().default(48),
      rayColumns: z.number().int().min(1).max(33).optional().default(9),
      rayRows: z.number().int().min(1).max(33).optional().default(5),
    },
    annotations: READ,
  },
  {
    name: "scan_visible",
    method: "vision.scan",
    title: "Scan what the player can actually see",
    description:
      "Client-only, READ-ONLY. Sweep the field of view and list the blocks the rays actually land on, " +
      "nearest first — what the player can point at, not what the loaded chunks happen to contain. Every " +
      "entry is a real sighting: a ray reached it, so the line of sight is clear by construction, and a " +
      "trunk behind a hill or a drop behind a wall is simply not in the list. Each block carries its " +
      "distance, whether it is 'wet' (sitting in liquid), whether it is 'inReach', and its 'hardness' — " +
      "the facts to choose between, since this call recommends nothing and decides nothing. Turn the " +
      "camera (control_look_at) and scan again to look somewhere else; that is the same loop a player " +
      "runs with their eyes, and it is the honest way to pick the next thing to mine. Also lists visible " +
      "entities (in the cone, nothing solid between) and what the crosshair is on.\n" +
      "The rays pass THROUGH blocks that do not occlude — leaves, glass, water, vines, plants — so a trunk " +
      'under its own canopy comes back as a sighting with through:["minecraft:oak_leaves"], and the foliage ' +
      "comes back with transparent:true. That is usually what you want to see: the tree you are looking at " +
      "is visible through the leaves, and the leaves are the thing to clear first when the shot is blocked. " +
      "What occludes still ends the ray, so nothing is reported that the eye genuinely cannot reach.",
    inputSchema: {
      maxDistance: z.number().min(1).max(96).optional().default(24).describe("How far the rays travel."),
      rayColumns: z.number().int().min(1).max(41).optional().default(13).describe("Rays across the view; more is a finer sweep."),
      rayRows: z.number().int().min(1).max(21).optional().default(7).describe("Rays down the view."),
      ids: z
        .array(z.string())
        .optional()
        .describe(
          'Keep only these block ids, e.g. ["minecraft:oak_log"]. * is a wildcard anywhere: ' +
            '["minecraft:*_log"] is every wood type, ["minecraft:oak_*"] every oak block. Blocks that are ' +
            "filtered out are still seen through, so the filter does not hide what is behind them.",
        ),
      maxResults: z.number().int().min(1).max(64).optional().default(24),
    },
    annotations: READ,
  },

  // ===== actions (client, non-blocking) ======================================================
  {
    name: "run_plan",
    method: "action.do",
    title: "Run a plan of steps (returns immediately)",
    description:
      "Client-only, NON-BLOCKING. Submit a list of steps YOU compose, in order, and return at once — the plan then runs on its own while you keep watching and stay free to overrule it. This is the main way to act smoothly: a whole 'walk over, mine three logs, pick up the drops' flow costs one call instead of four, and the steps run back-to-back on the game thread with nothing drifting in between. " +
      'It is not a behaviour and knows nothing about trees or mobs: it runs exactly the steps you list, in the order you list them, and reports each one. A step is either {"action":"<module>", ...its params} for the multi-tick modules (moveTo, dig, attack, useFor, craft — same parameters as calling them directly), or {"rpc":"<any method>", ...its params} fired inline (inventory.selectHotbar, interact.placeBlock, control.lookAt, ui.clickSlot, control.setInput...). ' +
      'Because a module step is built when its turn comes, it may aim at a symbolic target: {"action":"moveTo","target":"nearest_drop"} resolves against the world AFTER the steps before it ran. ' +
      "Stop conditions are yours to declare, in guard: abortIfHealthBelow / abortIfAirBelow / abortIfDead end the run early and hand control straight back. A plan runs unattended for tens of seconds, so declare them rather than hoping. onFailure:'stop' (default) ends the run when a step fails, 'continue' presses on, and one step can be marked optional. " +
      "Comes back as soon as the plan is in charge, with a note and a first observe snapshot. Follow it with action_status: progress.completed / progress.total and progress.steps show which step is running and how the finished ones went, and progress.phase is that step's own live detail. To change course mid-flight, just submit another action or another plan — it supersedes this one on the next tick — or call action_cancel. A guard tripping, a failed step (with onFailure:'stop') or the plan's own budget ends the plan by itself and reports stoppedBy." +
      OBSERVE_NOTE,
    inputSchema: {
      steps: z
        .array(
          z
            .object({
              action: z
                .string()
                .optional()
                .describe(
                  "A module to run: moveTo, dig, attack, useFor, craft. Every sibling key is that module's parameter, exactly as if you called it directly. Anything not on that list was a composite or a reflex — compose it out of these, or write it as a rule.",
                ),
              rpc: z
                .string()
                .optional()
                .describe(
                  'Any other method to fire inline, e.g. "inventory.selectHotbar", "interact.placeBlock", "control.lookAt". Sibling keys are its parameters.',
                ),
              optional: z.boolean().optional().describe("If true, a failure in this step does not stop the plan."),
              timeoutSeconds: z.number().int().min(1).max(300).optional().describe("Budget for this one step."),
            })
            .passthrough(),
        )
        .min(1)
        .max(8)
        .describe(
          "The steps, in order — at most 8. Keep plans SHORT: every step after the first is written before " +
            "the first one's outcome is known, so a long plan is a guess that the world will move out from " +
            "under. Send a few steps, read what came back, then send the next. Give exactly one of 'action' " +
            "or 'rpc' per step.",
        ),
      onFailure: z
        .enum(["stop", "continue"])
        .optional()
        .default("stop")
        .describe("What a failed step does: end the plan (default) or carry on to the next one."),
      guard: z
        .object({
          abortIfHealthBelow: z
            .number()
            .min(0)
            .max(20)
            .optional()
            .describe("End the plan when health drops below this. 14 is a reasonable 'stop what I am doing' line."),
          abortIfAirBelow: z
            .number()
            .int()
            .min(0)
            .max(300)
            .optional()
            .describe("End the plan when air drops below this (0-300). 120 leaves about 6 seconds — set it whenever a step might take you under water."),
          abortIfDead: z.boolean().optional().describe("End the plan if the player dies."),
        })
        .optional()
        .describe("Your own stop conditions for the run. Declare them; do not hope."),
      timeoutSeconds: z.number().int().min(1).max(600).optional().default(120).describe("Budget for the whole plan."),
      waitSeconds: waitSeconds(600),
    },
    annotations: WRITE,
  },

  {
    name: "move_to",
    method: "action.moveTo",
    title: "Walk to a position (returns immediately)",
    description:
      "Client-only, NON-BLOCKING. Ask the player to walk to within 'reachRadius' of a position; returns as soon as the walk is under way. Follow it with action_status — state 'done' with detail 'reached' (or 'no_path' / 'stuck' / 'timeout') is how it turned out, 'running' means still walking. " +
      "Give x/y/z, or 'target' for a shorthand (nearest_hostile / nearest_drop / nearest_animal / looking_at) — handy inside a run_plan, where the shorthand resolves after the earlier steps ran. " +
      "It walks and nothing else: it does not open doors, fight, or pick what to walk to for you." +
      OBSERVE_NOTE,
    inputSchema: {
      ...goalArgs(),
      reachRadius: z.number().min(0).max(16).optional().default(1).describe("Stop when within this many blocks of the target."),
      sprint: z.boolean().optional().default(false),
      timeoutSeconds: z.number().int().min(1).max(120).optional().default(30),
      waitSeconds: waitSeconds(120),
    },
    annotations: WRITE,
  },
  {
    name: "dig",
    method: "action.dig",
    title: "Break one block, from where you stand (returns immediately)",
    description:
      "Client-only, NON-BLOCKING. Break exactly the one block named, from where the player already stands: " +
      "it holds the best hotbar tool for that block, keeps the crosshair on it, and stops the moment the " +
      "block is gone. It NEVER walks and NEVER clears anything out of the way — closing the distance is a " +
      "move_to, and digging through the vine in front of a trunk is a dig of your own on whatever is in the " +
      "way. Give x/y/z, or 'target' (looking_at, or visible_log for one you can actually see).\n" +
      "Returns 'mined', or 'out_of_reach' (walk closer — it will not come to you), or 'blocked' with " +
      "blockedBy/blockedAt naming the block the click would actually hit instead (dig that one, then come " +
      "back), or 'interrupted', or 'timeout'. While it runs, progress.crosshairOnBlock says what is in the way." +
      COMPOSE_NOTE +
      OBSERVE_NOTE,
    inputSchema: {
      ...goalArgs(),
      timeoutSeconds: z.number().int().min(1).max(120).optional().default(30),
      waitSeconds: waitSeconds(120),
    },
    annotations: WRITE,
  },
  {
    name: "attack",
    method: "action.attack",
    title: "Land one hit on an entity (returns immediately)",
    description:
      "Client-only, NON-BLOCKING. One swing, done properly: aim at the entity, wait for the attack bar to " +
      "be full, swing, then wait for it to refill before returning. It never walks, never strafes, never " +
      "picks a target and never decides the fight is over — closing the distance is a move_to, backing off " +
      "is a move_to, and 'keep hitting until it dies' is calling this again. Waiting on both sides of the " +
      "swing is what makes every hit it lands a full-damage one.\n" +
      "Give 'uuid', or 'target' (nearest_hostile is the usual one when something is chewing on you). " +
      "Returns 'hit' with damageDealt measured from the target's health bar, or 'killed', 'out_of_reach' " +
      "(it moved — close the gap yourself), 'cooldown', 'target_gone', 'not_found'." +
      OBSERVE_NOTE,
    inputSchema: {
      uuid: z.string().optional().describe("Entity UUID to hit. Give this or 'target'."),
      target: z.string().optional().describe(`A shorthand instead of 'uuid': ${TARGET_SPEC}.`),
      timeoutSeconds: z.number().int().min(1).max(60).optional().default(10),
      waitSeconds: waitSeconds(60),
    },
    annotations: WRITE,
  },
  {
    name: "batch",
    method: "rpc.batch",
    title: "Run several calls at once (parallel)",
    description:
      "Dispatch up to 24 calls in ONE round trip, concurrently — a slow one does not hold up a fast one. " +
      "This is how to read and act at the same time without serialising yourself: poll the running action, " +
      "read the watch stream and scan the view together in a single call, instead of three round trips one " +
      "after another. Each result is the full envelope it would have had on its own ({ok, result} or " +
      "{ok, error}) in the order the calls were listed, so a failure shows up as that call failing rather " +
      "than as the batch failing. It removes the round trips, not the physics: game-side effects still " +
      "land on the game thread one tick at a time.",
    inputSchema: {
      calls: z
        .array(
          z.object({
            method: z
              .string()
              .describe('Any RPC method, e.g. "action.status", "action.observe", "vision.scan", "player.getState".'),
            params: z.record(z.string(), z.unknown()).optional().describe("That method's parameters."),
          }),
        )
        .min(1)
        .max(24)
        .describe("The calls to run, in parallel."),
    },
    annotations: READ,
  },

  {
    name: "action_status",
    method: "action.status",
    title: "Current action status",
    description:
      "Client-only, READ-ONLY. The running action's state, its settled result (if any), and the live " +
      'observation snapshot under "observe" (vitals, hostiles, drops, crosshair, what the eye can see, ' +
      "progress, rolling log, danger level). Nothing blocks, so this is how you follow an action: poll it " +
      'while the action runs. Pass "sinceSeq" — the observe.seq you read last time — and the watch comes ' +
      "back as a stream rather than a snapshot: newEvents lists every change since then (a hit taken, air " +
      "ticking down, food slipping, a step into new ground) with its own sequence number, so nothing has " +
      "to be caught at the exact moment it was true.",
    inputSchema: {
      sinceSeq: z
        .number()
        .int()
        .min(0)
        .optional()
        .describe(
          "The observe.seq from your last read. Omit for the current snapshot; pass it to also get every change since.",
        ),
    },
    annotations: READ,
  },

  {
    name: "rules_set",
    method: "rules.set",
    title: "Set your own reflexes (replaces the whole set)",
    description:
      "Write a set of standing rules — your own reflexes, in your own words — and the mod evaluates them " +
      "every tick and fires the action the moment the condition holds. This is how a bot behaves like a " +
      "person doing something routine: not by deciding again on every read, but by having decided once. " +
      'Examples: {"name":"get out of water","when":{"airBelow":100},"then":{"rpc":"control.setInput","jump":true}} ' +
      '(holding jump swims up) and {"name":"break off","when":{"healthBelow":10,"hostileWithin":4},' +
      '"then":{"rpc":"control.setInput","back":true}} (walking backwards, away from it). ' +
      `Conditions (any subset; all that are given must hold): ${RULES_CONDITIONS}. ` +
      '\'then\' is either {action:"<module>", ...its params} — a real module, same params as calling it — ' +
      'or {rpc:"<method>", ...}. Optional per rule: cooldownSeconds (default 3), delayTicks (default 3, ' +
      "because a person does not react on the very tick they notice), once (fire at most once).\n" +
      "NOTHING IS BUILT IN and this replaces the whole set: rules you no longer want simply stop existing, " +
      "which also means an empty array means no reflexes at all. Every firing shows up in the observation " +
      "stream as an event with kind 'rule', so you can watch your own rules work — and see them go wrong.",
    inputSchema: {
      rules: z
        .array(
          z
            .object({
              name: z.string().optional().describe("A label; it is what the stream shows when it fires."),
              when: z.object({}).passthrough().describe(`Conditions — any subset of: ${RULES_CONDITIONS}`),
              then: z
                .object({})
                .passthrough()
                .describe('{action:"useFor","ticks":35} for a module, or {rpc:"control.setInput","jump":true} for any method.'),
              cooldownSeconds: z.number().min(0).max(600).optional().describe("Ticks between firings, in seconds. Default 3."),
              delayTicks: z.number().int().min(0).max(40).optional().describe("Notice-to-act pause. Default 3."),
              once: z.boolean().optional().describe("Fire at most once, then stop."),
            })
            .passthrough(),
        )
        .max(16)
        .describe("The complete set, replacing whatever was there. Empty array = no rules."),
    },
    annotations: WRITE,
  },
  {
    name: "rules_list",
    method: "rules.list",
    title: "List the rules that are running",
    description:
      "Client-only, READ-ONLY. The rules currently in force, with their conditions, actions, cooldowns and " +
      "how many times each has fired. Read it after rules_set to confirm what you actually installed.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "rules_clear",
    method: "rules.clear",
    title: "Remove all rules",
    description:
      "Client-only. Drop the whole rule set, so nothing acts between your calls again. Equivalent to " +
      "rules_set with an empty array; use it when the situation has changed enough that your reflexes are " +
      "now the wrong ones.",
    inputSchema: {},
    annotations: WRITE,
  },
  {
    name: "help",
    method: "action.help",
    title: "What can I do, and what will it say back",
    description:
      "Client-only, READ-ONLY. The whole module surface in one call: every module with what it does, what " +
      "it takes and the states it returns; the target vocabulary; the fields vision.scan reports; the " +
      "conditions a rule can use; the plan limit; and how the watch stream works. Read this once instead " +
      "of inferring the API from a pile of tool descriptions — it is generated next to the modules " +
      "themselves, so it cannot drift from them.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "observe",
    method: "action.observe",
    title: "Observe the world right now",
    description:
      "Client-only, READ-ONLY. A live situational snapshot, sampled every tick: your vitals (health/food/air/position), " +
      "nearby hostile mobs with distance, dropped items on the ground, what the crosshair is on, whether a blocking " +
      "action is running and what it is doing, and a rolling log of notable moments. Use it to look before you act, or " +
      'to keep watching while another call is in flight; "observe.danger" is 0 (fine) / 1 (caution) / 2 (act now), ' +
      "and dangerReason names the cause (low_health / drowning air=N / hostile_close / dead). " +
      "Also reports what is going WRONG in real time, which is the part a health bar hides: an 'anomalies' map " +
      "(dead / drowning / falling_hard / in_lava / suffocating / on_fire / freezing / below_world / starving / hungry " +
      "/ low_health / taking_damage / stuck / carried_along), each with severity, the evidence, and (where there is " +
      "one) the entity that caused it under 'at'. There is no recommended remedy and no 'nextAction' — deciding " +
      "what to do about it is yours, which is what rules_set is for. A cleared anomaly stays listed for a few " +
      "seconds marked 'stale' so a fast one (a single hit) is not missed between polls. 'summary' is the whole " +
      "situation in one line.\n" +
      "This is the ONLY source of awareness: the mod never acts on its own — it will not surface, fight, retreat or " +
      "save itself unless you call for it — so read this, then issue the short action you want. " +
      "Works whether idle or mid-action, and it is sampled EVERY tick — including while nothing is running — " +
      'so the watch never stops. Pass "sinceSeq" (the seq you read last time) to get newEvents: every change ' +
      "since then, each with its own sequence number, instead of having to poll fast enough to catch things " +
      "as they flash by. The snapshot also carries 'visible' — what the eye can actually see right now — so " +
      "you never have to stop watching in order to go and look.",
    inputSchema: {
      sinceSeq: z
        .number()
        .int()
        .min(0)
        .optional()
        .describe("The seq from your last read; with it you also get every change since (newEvents)."),
    },
    annotations: READ,
  },
  {
    name: "action_cancel",
    method: "action.cancel",
    title: "Cancel the current action",
    description: "Client-only. Cancel the running blocking action and release movement/mining.",
    inputSchema: {},
    annotations: WRITE,
  },

  {
    name: "use_for",
    method: "action.useFor",
    title: "Hold the use button for N ticks (returns immediately)",
    description:
      "Client-only, NON-BLOCKING. Button down, wait, button up — the one gesture behind eating, drinking, " +
      "drawing a bow and holding up a shield. It does not look at what is in the hand and does not judge " +
      "whether that food is worth eating (20 ticks is a second; a full meal is about 32). Select the item " +
      "first if it matters, or leave the whole thing to a rule.\n" +
      "Returns 'used' with holdingBefore/holdingAfter and 'consumed' — which is what separates a meal from " +
      "standing there holding a torch — or 'timeout'." +
      OBSERVE_NOTE,
    inputSchema: {
      ticks: z.number().int().min(1).max(1200).optional().default(35).describe("How long to hold. Eating takes about 32 ticks."),
      timeoutSeconds: z.number().int().min(1).max(60).optional().default(10),
      waitSeconds: waitSeconds(60),
    },
    annotations: WRITE,
  },
  
  // ===== ui (client, menus) ==================================================================
  {
    name: "menu_status",
    method: "ui.state",
    title: "Current screen / menu, and what is in every slot",
    description:
      "Client-only, READ-ONLY. Which GUI screen is open ('none' = normal gameplay), which container menu is " +
      "active, what is on the cursor, and the contents of every slot by index — which is the address " +
      "click_slot takes. Empty slots are listed too (index only), because the index is what matters.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "click_slot",
    method: "ui.clickSlot",
    title: "Click one slot in the open menu",
    description:
      "Client-only. One mouse click in the open container, through the same call vanilla's mouse handler " +
      "makes. This is every container act there is: taking a crafted item (slot 0 of a crafting menu), " +
      "pulling something out of a chest, feeding a furnace, moving a stack between the bag and the hotbar. " +
      "Read menu_status first for the slot list. Returns what was in the slot before and after and what is " +
      "on the cursor now, so you can see whether the server accepted it. Do not close a screen while " +
      "carrying something on the cursor — the game deletes it.",
    inputSchema: {
      slot: z.number().int().min(0).describe("Slot index from menu_status."),
      button: z.number().int().min(0).max(8).optional().default(0).describe("0 = left click, 1 = right click."),
      mode: z
        .enum(["pickup", "quick_move", "swap", "throw", "clone"])
        .optional()
        .default("pickup")
        .describe("pickup = normal click; quick_move = shift-click, straight into the inventory."),
    },
    annotations: WRITE,
  },
  {
    name: "open_inventory",
    method: "ui.openInventory",
    title: "Open the player inventory",
    description: "Client-only. Open the player inventory / 2x2 crafting screen (like pressing E).",
    inputSchema: {},
    annotations: WRITE,
  },
  {
    name: "close_screen",
    method: "ui.close",
    title: "Close the current screen",
    description: "Client-only. Close any open screen/container (like pressing Esc or backing out of a chest).",
    inputSchema: {},
    annotations: WRITE,
  },

  // ===== events ==============================================================================
  {
    name: "poll_events",
    method: "events.getRecent",
    title: "Poll recent game events",
    description:
      'Return recently observed events from the in-mod ring buffer. These are the events a client can see for itself: ' +
      '"chat" and "system_message" (messages received), "player_damage" and "player_death" (your own health dropping, ' +
      'derived client-side), and "dimension_change". Filter by type and/or pass sinceId to get only events newer than one ' +
      "you have already seen.",
    inputSchema: {
      limit: z.number().int().min(1).max(500).optional().default(50),
      types: z.array(z.string()).optional().describe('Event type filter, e.g. ["chat","player_damage","entity_death"].'),
      sinceId: z.number().int().min(0).optional().describe("Only return events with id greater than this."),
    },
    annotations: READ,
  },
];
