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

const dimensionOpt = {
  dimension: z
    .string()
    .optional()
    .describe('Dimension id, e.g. "minecraft:overworld", "minecraft:the_nether". Defaults to the current/overworld dimension.'),
};

const READ = { readOnlyHint: true } as const;
const WRITE = { destructiveHint: true } as const;

/**
 * How long a blocking action's HTTP call waits before returning `state:"running"` so the caller can
 * keep polling. Every blocking action returns a live observation snapshot either way, so a short
 * wait means "watch while it happens" instead of "wait blindly until it is over".
 */
const waitSeconds = (maxTimeout: number) =>
  z
    .number()
    .int()
    .min(0)
    .max(maxTimeout)
    .optional()
    .describe(
      `Optional cap on how long this HTTP call blocks before giving up and returning state:"running". ` +
        `OMIT THIS for short-step composition — the call then returns only once the step has settled, which is what ` +
        `you want before composing the next step. ` +
        `Only set it for a long action you intend to poll; note the action keeps running in the background while it ` +
        `does, so the next action call will fail with "already running" until it settles or you call action_cancel. ` +
        `If you do set it, make it LARGER than timeoutSeconds or the step gets cut off mid-flight. ` +
        `Either way the result carries an "observe" snapshot of the world (health, threats, drops, crosshair, ` +
        `progress, rolling log) plus "elapsedMs"/"remainingMs"/"progress".`,
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
  "mine_block per log (move_to first if the trunk is a different level, re-read world.find_blocks after " +
  "each one, since felling a trunk exposes the next), then move_to onto the drops so the player picks " +
  "them up, then inventory/craft. Same for a fight: retrieve the mob, move_to into range while it is " +
  "still where you last saw it, swing_at_entity, then re-observe and recompose. Keep each call short, " +
  "read the world between steps, and change your plan when it no longer matches.";

/** The live-watch payload attached to every action result. */
const OBSERVE_NOTE =
  ' The result includes an "observe" snapshot sampled every tick while it ran: vitals, nearby hostiles, drops on the ' +
  'ground, what the crosshair is on, the task\'s own "progress", and a rolling "log" of notable moments — so the ' +
  'world is visible during the action instead of only after it. Poll action_status / observe to keep watching, and ' +
  'read observe.danger (0 fine / 1 caution / 2 act now) to decide whether to action_cancel.';

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

  // ===== actions (client, blocking) ==========================================================
  {
    name: "move_to",
    method: "action.moveTo",
    title: "Walk to a position (blocking)",
    description:
      "Client-only, BLOCKING. Walk the player to within 'reachRadius' of a target and return when the walk settles (reached / no_path / timeout), or earlier if 'waitSeconds' caps the wait." +
      OBSERVE_NOTE,
    inputSchema: {
      ...vec3(),
      reachRadius: z.number().min(0).max(16).optional().default(1).describe("Stop when within this many blocks of the target."),
      sprint: z.boolean().optional().default(false),
      timeoutSeconds: z.number().int().min(1).max(120).optional().default(30),
      waitSeconds: waitSeconds(120),
    },
    annotations: WRITE,
  },
  {
    name: "mine_block",
    method: "action.mineBlock",
    title: "Mine a block (blocking)",
    description:
      "Client-only, BLOCKING. One block, start to finish: walk into reach (A*), face it, auto-select the best tool in the hotbar, mine with realistic survival timing, and return when it is gone. Returns state mined / unreachable / timeout. " +
      "It mines exactly the one block you named — it does not follow a vein or fell a tree for you." +
      COMPOSE_NOTE +
      OBSERVE_NOTE,
    inputSchema: {
      ...vec3(),
      timeoutSeconds: z.number().int().min(1).max(120).optional().default(30),
      waitSeconds: waitSeconds(120),
    },
    annotations: WRITE,
  },
  {
    name: "action_status",
    method: "action.status",
    title: "Current action status",
    description:
      "Client-only. Report the running/blocking action's state, its settled result (if any), and a full live " +
      'observation snapshot under "observe" (vitals, hostiles, drops, crosshair, progress, rolling log, danger level). ' +
      "Poll this while a long action runs to watch the world instead of waiting blindly.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "react",
    method: "action.react",
    title: "Deal with whatever is wrong, now",
    description:
      "Client-only. The fast path for acting on the live state: the observation already works out the " +
      "single best thing to do about the current situation, and this runs it. No arguments — it reads " +
      'the remedy from observe.nextAction (tool + arguments, with the offending entity\'s UUID already ' +
      "filled in) and executes it. Use it the moment observe.danger hits 2 rather than assembling an " +
      "action yourself.\n" +
      "The remedy goes out after a short human reaction time (150-350ms) rather than on the exact tick " +
      "the state changed, because instant reactions are the clearest machine tell. Blocking: returns " +
      '{queued, args, inTicks, because} once the reaction is scheduled. Errors if observe.nextAction is ' +
      "empty, i.e. there is nothing worth doing right now.\n" +
      "Remedies it can run: surface (drowning), water_bucket_save (a fall already long enough to hurt), " +
      "eat (starving), retreat_from (low health with a mob on you), action_cancel (dead, or wedged). " +
      "It never interrupts an action you asked for — if one is running, cancel it first.",
    inputSchema: {},
    annotations: WRITE,
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
      "/ low_health / taking_damage / stuck / carried_along), each with severity, the evidence, and the remedy to " +
      "call. A cleared anomaly stays listed for a few seconds marked 'stale' so a fast one (a single hit) is not " +
      "missed between polls. 'summary' is the whole situation in one line, and 'nextAction' is the single call that " +
      "deals with it, arguments included — pass it to 'react' to act immediately.\n" +
      "This is the ONLY source of awareness: the mod never acts on its own — it will not surface, fight, retreat or " +
      "save itself unless you call for it — so read this, then issue the short action you want. " +
      "Works whether idle or mid-action.",
    inputSchema: {},
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
    name: "eat",
    method: "action.eat",
    title: "Eat food until full (blocking)",
    description:
      "Client-only, BLOCKING. Hold the use key on the best food in the hotbar until the hunger bar is full (or no food is left). Uses the vanilla eating timing.",
    inputSchema: {
      timeoutSeconds: z.number().int().min(1).max(60).optional().default(20),
      waitSeconds: waitSeconds(60),
    },
    annotations: WRITE,
  },
  {
    name: "swing_at_entity",
    method: "action.swing",
    title: "Hit an entity (blocking, short)",
    description:
      "Client-only, BLOCKING. One module, no policy: aim at the entity and swing until 'hits' hits land or the budget runs out. It never walks, never strafes and never picks a target — closing the distance is a move_to, stepping back is a move_to, and 'keep hitting until it dies' is calling this again. " +
      "Only swings when the target is within reach and the attack cooldown is charged, so every hit is a full-damage one. " +
      "Returns state 'hit' (hits met) / 'killed' / 'out_of_reach' (it moved — close the gap yourself) / 'budget' / 'target_gone' / 'not_found', plus 'damageDealt' measured from the target's health bar." +
      OBSERVE_NOTE,
    inputSchema: {
      uuid: z.string().describe("Entity UUID to hit."),
      hits: z.number().int().min(0).max(64).optional().default(1).describe("Stop after this many landed hits (0 = keep swinging for the whole budget)."),
      timeoutSeconds: z.number().int().min(1).max(60).optional().default(10),
      waitSeconds: waitSeconds(60),
    },
    annotations: WRITE,
  },
  {
    name: "retreat_from",
    method: "action.retreat",
    title: "Back away from something (blocking, short)",
    description:
      "Client-only, BLOCKING. Withdraw roughly 'distance' blocks, on the far side of the player from the " +
      "given entity or coordinate, then stop. Picks shelter that is walkable — it will not back into " +
      "water or off a ledge — and re-aims around the arc when the way straight back is blocked. " +
      "Returns state 'withdrew' / 'no_threat' (the thing is gone) / 'no_room' / 'no_path' / 'timeout'. " +
      "Nothing retreats on its own: watch health and nearby hostiles in the observation feed and decide." +
      OBSERVE_NOTE,
    inputSchema: {
      uuid: z.string().optional().describe("Entity to back away from. Give this or x/y/z."),
      x: z.number().optional(),
      y: z.number().optional(),
      z: z.number().optional(),
      distance: z.number().min(1.5).max(64).optional().default(6).describe("How far away to end up."),
      timeoutSeconds: z.number().int().min(1).max(120).optional().default(20),
      waitSeconds: waitSeconds(120),
    },
    annotations: WRITE,
  },
  {
    name: "surface",
    method: "action.surface",
    title: "Swim up for air (blocking, short)",
    description:
      "Client-only, BLOCKING. Swim straight up until the head is out of the water and the air bar has " +
      "refilled, then return. No walking, no sprinting, so it rises instead of drifting sideways. " +
      "Returns state 'surfaced' / 'not_in_water' / 'timeout'. " +
      "There is deliberately no drowning reflex in the mod — observe publishes 'air' and " +
      "'eyesUnderWater', and dangerReason says 'drowning air=N — action_surface' when it is time." +
      OBSERVE_NOTE,
    inputSchema: {
      timeoutSeconds: z.number().int().min(1).max(120).optional().default(20),
      waitSeconds: waitSeconds(120),
    },
    annotations: WRITE,
  },
  {
    name: "water_bucket_save",
    method: "action.mlg",
    title: "Water-bucket fall save (blocking, short)",
    description:
      "Client-only, BLOCKING. The 'MLG water' save, on demand: waits until the ground is inside placing " +
      "range, empties a water bucket straight down, lands in it, then scoops the source back up and " +
      "restores the hotbar slot. Needs a water_bucket in the hotbar. " +
      "Returns state 'saved' / 'nothing_to_save' (already on the ground) / 'no_water_bucket' / " +
      "'never_in_reach' (not actually falling). Nothing fires this automatically — call it from a fall " +
      "you chose to take, before you are too close to the ground to place the water." +
      OBSERVE_NOTE,
    inputSchema: {
      timeoutSeconds: z.number().int().min(1).max(60).optional().default(20),
      waitSeconds: waitSeconds(60),
    },
    annotations: WRITE,
  },

  // ===== ui (client, menus) ==================================================================
  {
    name: "menu_status",
    method: "ui.state",
    title: "Current screen / menu",
    description: "Client-only. Report which GUI screen is open ('none' = normal gameplay) and which container menu is active.",
    inputSchema: {},
    annotations: READ,
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
