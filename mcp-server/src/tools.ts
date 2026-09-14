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

/** The live-watch payload attached to every action result. */
const OBSERVE_NOTE =
  ' The result includes an "observe" snapshot sampled every tick while it ran: vitals, nearby hostiles, drops on the ' +
  'ground, what the crosshair is on, the task\'s own "progress", and a rolling "log" of notable moments — so the ' +
  'world is visible during the action instead of only after it. Poll action_status / observe to keep watching, and ' +
  'read observe.danger (0 fine / 1 caution / 2 act now) to decide whether to action_cancel.';

// ----- catalogue ------------------------------------------------------------------------------

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

  // ===== player (client local player) ========================================================
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
      "Client-only, BLOCKING. The whole 'dig this block' intent in one call: walk into reach (A*), face the block, auto-select the best tool in the hotbar, then mine with realistic survival timing until the block is gone. Returns state mined / unreachable / timeout." +
      OBSERVE_NOTE,
    inputSchema: {
      ...vec3(),
      timeoutSeconds: z.number().int().min(1).max(120).optional().default(30),
      waitSeconds: waitSeconds(120),
    },
    annotations: WRITE,
  },
  {
    name: "collect_items",
    method: "action.collectItems",
    title: "Collect nearby drops (blocking)",
    description:
      "Client-only, BLOCKING. Walk over dropped item entities within 'radius' so the player picks them up; returns when none remain in range or the budget elapses." +
      OBSERVE_NOTE,
    inputSchema: {
      radius: z.number().min(1).max(48).optional().default(16),
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
    name: "observe",
    method: "action.observe",
    title: "Observe the world right now",
    description:
      "Client-only, READ-ONLY. A live situational snapshot, sampled every tick: your vitals (health/food/air/position), " +
      "nearby hostile mobs with distance, dropped items on the ground, what the crosshair is on, whether a blocking " +
      "action is running and what it is doing, and a rolling log of notable moments. Use it to look before you act, or " +
      'to keep watching while another call is in flight; "observe.danger" is 0 (fine) / 1 (caution) / 2 (act now). ' +
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
    name: "mine_vein",
    method: "action.mineVein",
    title: "Mine a whole vein/tree (blocking)",
    description:
      "Client-only, BLOCKING. Mine a connected cluster of same-id blocks — a whole tree, an ore vein, a stack of logs. Walks between blocks as needed and follows the cluster to exhaustion (bounded by 'max'). One call = 'chop that tree down'." +
      OBSERVE_NOTE,
    inputSchema: {
      ...vec3(),
      max: z.number().int().min(1).max(512).optional().default(64).describe("Maximum number of blocks to mine."),
      timeoutSeconds: z.number().int().min(1).max(300).optional().default(60),
      waitSeconds: waitSeconds(300),
    },
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
    name: "attack",
    method: "action.attack",
    title: "Fight an entity in short steps (blocking)",
    description:
      "Client-only, BLOCKING. Fights an entity by composing SHORT steps: each cycle closes the gap (A*) or trades blows once in range, then re-plans against where the target actually is now. " +
      "Swinging only happens when the attack cooldown is fully charged (no spam-clicking), and while fighting the bot moves like a person — reacts once, circle-strafes, steps back after landing a hit, jumps to crit. " +
      "The default budget is deliberately short (~10s) so the fight is a series of small, watchable calls rather than one long commit: call it again to continue, or stop and do something else. " +
      "For full manual control, compose 'approach_entity' and 'swing_at_entity' yourself instead." +
      OBSERVE_NOTE,
    inputSchema: {
      uuid: z.string().describe("Entity UUID to attack."),
      maxSwings: z.number().int().min(0).max(500).optional().default(0).describe("Stop after this many landed hits (0 = keep going until the budget runs out)."),
      timeoutSeconds: z.number().int().min(1).max(120).optional().default(10),
      waitSeconds: waitSeconds(120),
    },
    annotations: WRITE,
  },
  {
    name: "approach_entity",
    method: "action.approach",
    title: "Walk into range of an entity (blocking, short)",
    description:
      "Client-only, BLOCKING. One short step: walk until the entity is within 'reach' blocks, then return. Re-plans as the target moves instead of committing to a long route. " +
      "Returns state 'reached' / 'budget' (out of time, call again to keep closing) / 'not_found' / 'no_path'. " +
      "Compose it with swing_at_entity for full control over a fight, or use 'attack' to have that composed for you." +
      OBSERVE_NOTE,
    inputSchema: {
      uuid: z.string().describe("Entity UUID to approach."),
      reach: z.number().min(0.5).max(16).optional().default(2.5).describe("Stop when this close to the entity."),
      timeoutSeconds: z.number().int().min(1).max(60).optional().default(10),
      waitSeconds: waitSeconds(60),
    },
    annotations: WRITE,
  },
  {
    name: "swing_at_entity",
    method: "action.swing",
    title: "Hit an entity already in range (blocking, short)",
    description:
      "Client-only, BLOCKING. One short step: aim at an entity that is ALREADY within melee range and swing until 'swings' hits land or the step budget runs out. Never walks anywhere. " +
      "Returns state 'swung' (budget met) / 'killed' / 'out_of_reach' (target moved away — compose an approach_entity step) / 'budget' / 'target_gone'. " +
      "Moves like a person while fighting: reacts once, circle-strafes, steps back after a hit, jumps to crit. Pair with approach_entity to drive a fight yourself." +
      OBSERVE_NOTE,
    inputSchema: {
      uuid: z.string().describe("Entity UUID to hit."),
      swings: z.number().int().min(0).max(64).optional().default(1).describe("Stop after this many landed hits (0 = keep swinging for the whole step budget)."),
      timeoutSeconds: z.number().int().min(1).max(60).optional().default(10),
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
