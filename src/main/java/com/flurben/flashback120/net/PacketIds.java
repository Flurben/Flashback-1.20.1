package com.flurben.flashback120.net;

import net.minecraft.network.protocol.game.*;

/** Clientbound play packet ids of Minecraft 1.20.1 that the mod looks at without decoding packets. */
public final class PacketIds {

    public static final int BUNDLE_DELIMITER = 0;
    public static final int ADD_ENTITY = PacketCodec.idOf(ClientboundAddEntityPacket.class);
    public static final int ADD_PLAYER = PacketCodec.idOf(ClientboundAddPlayerPacket.class);
    public static final int AWARD_STATS = PacketCodec.idOf(ClientboundAwardStatsPacket.class);
    public static final int BLOCK_CHANGED_ACK = PacketCodec.idOf(ClientboundBlockChangedAckPacket.class);
    public static final int BLOCK_DESTRUCTION = PacketCodec.idOf(ClientboundBlockDestructionPacket.class);
    public static final int BLOCK_ENTITY_DATA = PacketCodec.idOf(ClientboundBlockEntityDataPacket.class);
    public static final int BLOCK_EVENT = PacketCodec.idOf(ClientboundBlockEventPacket.class);
    public static final int BLOCK_UPDATE = PacketCodec.idOf(ClientboundBlockUpdatePacket.class);
    public static final int BOSS_EVENT = PacketCodec.idOf(ClientboundBossEventPacket.class);
    public static final int CHUNKS_BIOMES = PacketCodec.idOf(ClientboundChunksBiomesPacket.class);
    public static final int COMMAND_SUGGESTIONS = PacketCodec.idOf(ClientboundCommandSuggestionsPacket.class);
    public static final int COMMANDS = PacketCodec.idOf(ClientboundCommandsPacket.class);
    public static final int CONTAINER_CLOSE = PacketCodec.idOf(ClientboundContainerClosePacket.class);
    public static final int CONTAINER_CONTENT = PacketCodec.idOf(ClientboundContainerSetContentPacket.class);
    public static final int CONTAINER_DATA = PacketCodec.idOf(ClientboundContainerSetDataPacket.class);
    public static final int CONTAINER_SLOT = PacketCodec.idOf(ClientboundContainerSetSlotPacket.class);
    public static final int COOLDOWN = PacketCodec.idOf(ClientboundCooldownPacket.class);
    public static final int CHAT_COMPLETIONS = PacketCodec.idOf(ClientboundCustomChatCompletionsPacket.class);
    public static final int CUSTOM_PAYLOAD = PacketCodec.idOf(ClientboundCustomPayloadPacket.class);
    public static final int DELETE_CHAT = PacketCodec.idOf(ClientboundDeleteChatPacket.class);
    public static final int DISCONNECT = PacketCodec.idOf(ClientboundDisconnectPacket.class);
    public static final int EXPLODE = PacketCodec.idOf(ClientboundExplodePacket.class);
    public static final int FORGET_CHUNK = PacketCodec.idOf(ClientboundForgetLevelChunkPacket.class);
    public static final int GAME_EVENT = PacketCodec.idOf(ClientboundGameEventPacket.class);
    public static final int HORSE_SCREEN = PacketCodec.idOf(ClientboundHorseScreenOpenPacket.class);
    public static final int KEEP_ALIVE = PacketCodec.idOf(ClientboundKeepAlivePacket.class);
    public static final int LEVEL_CHUNK = PacketCodec.idOf(ClientboundLevelChunkWithLightPacket.class);
    public static final int LEVEL_EVENT = PacketCodec.idOf(ClientboundLevelEventPacket.class);
    public static final int LEVEL_PARTICLES = PacketCodec.idOf(ClientboundLevelParticlesPacket.class);
    public static final int LIGHT_UPDATE = PacketCodec.idOf(ClientboundLightUpdatePacket.class);
    public static final int LOGIN = PacketCodec.idOf(ClientboundLoginPacket.class);
    public static final int MERCHANT_OFFERS = PacketCodec.idOf(ClientboundMerchantOffersPacket.class);
    public static final int MOVE_VEHICLE = PacketCodec.idOf(ClientboundMoveVehiclePacket.class);
    public static final int OPEN_BOOK = PacketCodec.idOf(ClientboundOpenBookPacket.class);
    public static final int OPEN_SCREEN = PacketCodec.idOf(ClientboundOpenScreenPacket.class);
    public static final int OPEN_SIGN_EDITOR = PacketCodec.idOf(ClientboundOpenSignEditorPacket.class);
    public static final int PING = PacketCodec.idOf(ClientboundPingPacket.class);
    public static final int GHOST_RECIPE = PacketCodec.idOf(ClientboundPlaceGhostRecipePacket.class);
    public static final int PLAYER_ABILITIES = PacketCodec.idOf(ClientboundPlayerAbilitiesPacket.class);
    public static final int PLAYER_CHAT = PacketCodec.idOf(ClientboundPlayerChatPacket.class);
    public static final int COMBAT_END = PacketCodec.idOf(ClientboundPlayerCombatEndPacket.class);
    public static final int COMBAT_ENTER = PacketCodec.idOf(ClientboundPlayerCombatEnterPacket.class);
    public static final int COMBAT_KILL = PacketCodec.idOf(ClientboundPlayerCombatKillPacket.class);
    public static final int PLAYER_INFO_REMOVE = PacketCodec.idOf(ClientboundPlayerInfoRemovePacket.class);
    public static final int PLAYER_INFO_UPDATE = PacketCodec.idOf(ClientboundPlayerInfoUpdatePacket.class);
    public static final int PLAYER_LOOK_AT = PacketCodec.idOf(ClientboundPlayerLookAtPacket.class);
    public static final int PLAYER_POSITION = PacketCodec.idOf(ClientboundPlayerPositionPacket.class);
    public static final int RECIPE = PacketCodec.idOf(ClientboundRecipePacket.class);
    public static final int RESOURCE_PACK = PacketCodec.idOf(ClientboundResourcePackPacket.class);
    public static final int RESPAWN = PacketCodec.idOf(ClientboundRespawnPacket.class);
    public static final int SECTION_BLOCKS = PacketCodec.idOf(ClientboundSectionBlocksUpdatePacket.class);
    public static final int SELECT_ADVANCEMENTS_TAB = PacketCodec.idOf(ClientboundSelectAdvancementsTabPacket.class);
    public static final int SET_CAMERA = PacketCodec.idOf(ClientboundSetCameraPacket.class);
    public static final int SET_CARRIED_ITEM = PacketCodec.idOf(ClientboundSetCarriedItemPacket.class);
    public static final int SET_CHUNK_CENTER = PacketCodec.idOf(ClientboundSetChunkCacheCenterPacket.class);
    public static final int SET_CHUNK_RADIUS = PacketCodec.idOf(ClientboundSetChunkCacheRadiusPacket.class);
    public static final int SET_DISPLAY_OBJECTIVE = PacketCodec.idOf(ClientboundSetDisplayObjectivePacket.class);
    public static final int SET_EXPERIENCE = PacketCodec.idOf(ClientboundSetExperiencePacket.class);
    public static final int SET_HEALTH = PacketCodec.idOf(ClientboundSetHealthPacket.class);
    public static final int SET_OBJECTIVE = PacketCodec.idOf(ClientboundSetObjectivePacket.class);
    public static final int SET_PLAYER_TEAM = PacketCodec.idOf(ClientboundSetPlayerTeamPacket.class);
    public static final int SET_SCORE = PacketCodec.idOf(ClientboundSetScorePacket.class);
    public static final int SET_TIME = PacketCodec.idOf(ClientboundSetTimePacket.class);
    public static final int SOUND_ENTITY = PacketCodec.idOf(ClientboundSoundEntityPacket.class);
    public static final int SOUND = PacketCodec.idOf(ClientboundSoundPacket.class);
    public static final int STOP_SOUND = PacketCodec.idOf(ClientboundStopSoundPacket.class);
    public static final int TAG_QUERY = PacketCodec.idOf(ClientboundTagQueryPacket.class);
    public static final int UPDATE_ADVANCEMENTS = PacketCodec.idOf(ClientboundUpdateAdvancementsPacket.class);
    public static final int UPDATE_RECIPES = PacketCodec.idOf(ClientboundUpdateRecipesPacket.class);

    private PacketIds() {}
}
