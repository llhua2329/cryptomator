/*******************************************************************************
 * Copyright (c) 2014 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *     Markus Kreusch - Refactored to use strategy pattern
 ******************************************************************************/
package org.cryptomator.ui.util.mount;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

public interface WebDavMounter {
	
	public static enum MountParam {MOUNT_NAME, WIN_DRIVE_LETTER}

	/**
	 * Tries to mount a given webdav share.
	 * 
	 * @param uri URI of the webdav share
	 * @param mountParams additional mount parameters, that might not get ignored by some OS-specific mounters.
	 * @return a {@link WebDavMount} representing the mounted share
	 * @throws CommandFailedException if the mount operation fails
	 * @throws IllegalArgumentException if mountParams is missing expected options
	 */
	WebDavMount mount(URI uri, Map<MountParam, Optional<String>> mountParams) throws CommandFailedException;

}
