/*-
 * #%L
 * rapidoid-inject
 * %%
 * Copyright (C) 2014 - 2018 Nikolche Mihajlovski and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package org.rapidoid.ioc.db;


import org.rapidoid.annotation.Authors;
import org.rapidoid.annotation.Since;
import org.rapidoid.ioc.IoC;
import org.rapidoid.ioc.Wired;

import java.util.Map;

@Authors("Nikolche Mihajlovski")
@Since("2.0.0")
public class Database {

	@Wired
	Transactor transactor;

	final Map<String, Table> tables = IoC.defaultContext().autoExpandingInjectingMap(Table.class);

	final Map<String, Relat> relations = IoC.defaultContext().autoExpandingInjectingMap(Relat.class);

}
