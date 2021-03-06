/*
 * Copyright 2011 buddycloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.buddycloud.pusher.utils;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.log4j.Logger;
import org.buddycloud.pusher.db.DataSource;


/**
 * @author Abmar
 *
 */
public class UserUtils {

	static Logger LOGGER = Logger.getLogger(UserUtils.class);
	
	public static String getUserEmail(String jid, DataSource dataSource) {
		PreparedStatement statement = null;
		try {
			statement = dataSource.prepareStatement(
					"SELECT email from notification_settings WHERE jid=?", 
					jid);
			ResultSet resultSet = statement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getString(1);
			}
			return null;
		} catch (SQLException e) {
			LOGGER.error("Could not get email from user [" + jid + "].", e);
			throw new RuntimeException(e);
		} finally {
			DataSource.close(statement);
		}
	}
}
