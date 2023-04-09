/*
 * Minecraft Forge
 * Copyright (c) 2016-2021.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.common.data;

import net.minecraft.client.resources.AssetIndex;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.client.resources.DefaultClientPackResources;
import net.minecraft.data.HashCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.FolderPackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.client.model.generators.ModelBuilder;
import net.minecraftforge.resource.ResourcePackLoader;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.data.DataProvider;
import net.minecraft.server.packs.resources.Resource;

/**
 * Enables data providers to check if other data files currently exist.
 */
public class ExistingFileHelper {

	public interface IResourceType {

		PackType getPackType();

		String getSuffix();

		String getPrefix();
	}

	public static class ResourceType implements IResourceType {

		final PackType packType;
		final String suffix, prefix;
		public ResourceType(PackType type, String suffix, String prefix) {
			this.packType = type;
			this.suffix = suffix;
			this.prefix = prefix;
		}

		@Override
		public PackType getPackType() { return packType; }

		@Override
		public String getSuffix() { return suffix; }

		@Override
		public String getPrefix() { return prefix; }
	}

	private final MultiPackResourceManager clientResources, serverData;
	private final boolean enable;
	private final Multimap<PackType, ResourceLocation> generated = HashMultimap.create();

	// fabric: added factory methods

	public static final String EXISTING_RESOURCES = "porting_lib.datagen.existing_resources";

	/**
	 * Create a helper with existing resources provided from a JVM argument.
	 * To use, a JVM argument mapping {@link ExistingFileHelper#EXISTING_RESOURCES the key}
	 * to the desired resource directory is required.
	 */
	public static ExistingFileHelper withResourcesFromArg() {
		String property = System.getProperty(EXISTING_RESOURCES);
		if (property == null)
			throw new IllegalArgumentException("Existing resources not specified with '" + EXISTING_RESOURCES + "' argument");
		Path path = Paths.get(property);
		if (!Files.isDirectory(path))
			throw new IllegalStateException("Path " + property + " is not a directory or does not exist");
		return withResources(path);
	}

	/**
	 * Create a helper for a standard mod environment.
	 * Assumes a file tree of: <pre>
	 *     - root
	 *         - run
	 *     - src
	 *         - main
	 *             - resources
	 * </pre>
	 * @deprecated use withResourcesFromArg
	 */
	@Deprecated(forRemoval = true)
	public static ExistingFileHelper standard() {
		return withResources(FabricLoader.getInstance()
				.getGameDir()
				.normalize()
				.getParent() // root
				.resolve("src")
				.resolve("main")
				.resolve("resources")
		);
	}

	/**
	 * Create a helper with the provided paths being used for resources.
	 */
	public static ExistingFileHelper withResources(Path... paths) {
		List<Path> resources = List.of(paths);
		return new ExistingFileHelper(resources, Set.of(), true, null, null);
	}

	public ExistingFileHelper(Collection<Path> existingPacks, Set<String> existingMods, boolean enable, @Nullable String assetIndex, @Nullable File assetsDir) {
		List<PackResources> candidateClientResources = new ArrayList<>();
		List<PackResources> candidateServerResources = new ArrayList<>();

		candidateClientResources.add(new VanillaPackResources(ClientPackSource.BUILT_IN, "minecraft", "realms"));
		if (assetIndex != null && assetsDir != null) {
			candidateClientResources.add(new DefaultClientPackResources(ClientPackSource.BUILT_IN, new AssetIndex(assetsDir, assetIndex)));
		}
		candidateServerResources.add(new VanillaPackResources(ServerPacksSource.BUILT_IN_METADATA, "minecraft"));
		for (Path existing : existingPacks) {
			File file = existing.toFile();
			PackResources pack = file.isDirectory() ? new FolderPackResources(file) : new FilePackResources(file);
			candidateClientResources.add(pack);
			candidateServerResources.add(pack);
		}
		for (String existingMod : existingMods) {
			ModContainer modFileInfo = FabricLoader.getInstance().getModContainer(existingMod).orElse(null);
			if (modFileInfo != null) {
				PackResources pack = ResourcePackLoader.createPackForMod(modFileInfo);
				candidateClientResources.add(pack);
				candidateServerResources.add(pack);
			}
		}

		this.clientResources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, candidateClientResources);
		this.serverData = new MultiPackResourceManager(PackType.SERVER_DATA, candidateServerResources);

		this.enable = enable;
	}

	private ResourceManager getManager(PackType packType) {
		return packType == PackType.CLIENT_RESOURCES ? clientResources : serverData;
	}

	private ResourceLocation getLocation(ResourceLocation base, String suffix, String prefix) {
		return new ResourceLocation(base.getNamespace(), prefix + "/" + base.getPath() + suffix);
	}

	/**
	 * Check if a given resource exists in the known resource packs.
	 *
	 * @param loc      the complete location of the resource, e.g.
	 *                 {@code "minecraft:textures/block/stone.png"}
	 * @param packType the type of resources to check
	 * @return {@code true} if the resource exists in any pack, {@code false}
	 *         otherwise
	 */
	public boolean exists(ResourceLocation loc, PackType packType) {
		if (!enable) {
			return true;
		}
		return generated.get(packType).contains(loc) || getManager(packType).hasResource(loc);
	}

	/**
	 * Check if a given resource exists in the known resource packs. This is a
	 * convenience method to avoid repeating type/prefix/suffix and instead use the
	 * common definitions in {@link ResourceType}, or a custom {@link IResourceType}
	 * definition.
	 *
	 * @param loc  the base location of the resource, e.g.
	 *             {@code "minecraft:block/stone"}
	 * @param type a {@link IResourceType} describing how to form the path to the
	 *             resource
	 * @return {@code true} if the resource exists in any pack, {@code false}
	 *         otherwise
	 */
	public boolean exists(ResourceLocation loc, IResourceType type) {
		return exists(getLocation(loc, type.getSuffix(), type.getPrefix()), type.getPackType());
	}

	/**
	 * Check if a given resource exists in the known resource packs.
	 *
	 * @param loc        the base location of the resource, e.g.
	 *                   {@code "minecraft:block/stone"}
	 * @param packType   the type of resources to check
	 * @param pathSuffix a string to append after the path, e.g. {@code ".json"}
	 * @param pathPrefix a string to append before the path, before a slash, e.g.
	 *                   {@code "models"}
	 * @return {@code true} if the resource exists in any pack, {@code false}
	 *         otherwise
	 */
	public boolean exists(ResourceLocation loc, PackType packType, String pathSuffix, String pathPrefix) {
		return exists(getLocation(loc, pathSuffix, pathPrefix), packType);
	}

	/**
	 * Track the existence of a generated file. This is a convenience method to
	 * avoid repeating type/prefix/suffix and instead use the common definitions in
	 * {@link ResourceType}, or a custom {@link IResourceType} definition.
	 * <p>
	 * This should be called by data providers immediately when a new data object is
	 * created, i.e. not during
	 * {@link DataProvider#run(HashCache) run} but instead
	 * when the "builder" (or whatever intermediate object) is created, such as a
	 * {@link ModelBuilder}.
	 * <p>
	 * This represents a <em>promise</em> to generate the file later, since other
	 * datagen may rely on this file existing.
	 *
	 * @param loc  the base location of the resource, e.g.
	 *             {@code "minecraft:block/stone"}
	 * @param type a {@link IResourceType} describing how to form the path to the
	 *             resource
	 */
	public void trackGenerated(ResourceLocation loc, IResourceType type) {
		this.generated.put(type.getPackType(), getLocation(loc, type.getSuffix(), type.getPrefix()));
	}

	/**
	 * Track the existence of a generated file.
	 * <p>
	 * This should be called by data providers immediately when a new data object is
	 * created, i.e. not during
	 * {@link DataProvider#run(HashCache) run} but instead
	 * when the "builder" (or whatever intermediate object) is created, such as a
	 * {@link ModelBuilder}.
	 * <p>
	 * This represents a <em>promise</em> to generate the file later, since other
	 * datagen may rely on this file existing.
	 *
	 * @param loc        the base location of the resource, e.g.
	 *                   {@code "minecraft:block/stone"}
	 * @param packType   the type of resources to check
	 * @param pathSuffix a string to append after the path, e.g. {@code ".json"}
	 * @param pathPrefix a string to append before the path, before a slash, e.g.
	 *                   {@code "models"}
	 */
	public void trackGenerated(ResourceLocation loc, PackType packType, String pathSuffix, String pathPrefix) {
		this.generated.put(packType, getLocation(loc, pathSuffix, pathPrefix));
	}

	@VisibleForTesting
	public Resource getResource(ResourceLocation loc, PackType packType, String pathSuffix, String pathPrefix) throws IOException {
		return getResource(getLocation(loc, pathSuffix, pathPrefix), packType);
	}

	@VisibleForTesting
	public Resource getResource(ResourceLocation loc, PackType packType) throws IOException {
		return getManager(packType).getResource(loc);
	}

	/**
	 * @return {@code true} if validation is enabled, {@code false} otherwise
	 */
	public boolean isEnabled() {
		return enable;
	}
}
