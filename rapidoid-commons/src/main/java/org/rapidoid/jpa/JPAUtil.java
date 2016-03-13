package org.rapidoid.jpa;

import org.rapidoid.annotation.Authors;
import org.rapidoid.annotation.Since;
import org.rapidoid.beany.Beany;
import org.rapidoid.u.U;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/*
 * #%L
 * rapidoid-commons
 * %%
 * Copyright (C) 2014 - 2016 Nikolche Mihajlovski and contributors
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

@Authors("Nikolche Mihajlovski")
@Since("5.1.0")
public class JPAUtil {

	private final EntityManager em;

	public JPAUtil(EntityManager em) {
		this.em = em;
	}

	public Object save(Object entity) {
		Object id = Beany.getIdIfExists(entity);

		if (id == null) {
			return insert(entity);

		} else {
			update(entity);
			return id;
		}
	}

	public void update(Object entity) {
		ensureNotInReadOnlyTransation();
		em.persist(entity);
	}

	public <E> List<E> getAll(Class<E> clazz, List<String> ids) {
		List<E> results = new ArrayList<E>();

		for (Object id : ids) {
			results.add(this.<E>get(clazz, id));
		}

		return results;
	}

	public <E> E get(Class<E> clazz, Object id) {
		E entity = find(clazz, id);

		if (entity == null) {
			throw U.rte("Cannot find entity with ID=%s", id);
		}

		return entity;
	}

	public Object insert(Object entity) {
		ensureNotInReadOnlyTransation();

		EntityTransaction tx = em.getTransaction();

		boolean txWasActive = tx.isActive();

		if (!txWasActive) {
			tx.begin();
		}

		try {
			em.persist(entity);
			em.flush();

			Object id = Beany.getId(entity);

			if (!txWasActive) {
				tx.commit();
			}

			return id;

		} catch (Throwable e) {
			if (!txWasActive) {
				if (tx.isActive()) {
					tx.rollback();
				}
			}
			throw U.rte("Transaction execution error, rolled back!", e);
		}
	}

	public <T> T find(Class<T> clazz, Object id) {
		return em.find(clazz, id);
	}

	@SuppressWarnings("unchecked")
	public <E> List<E> getAll() {
		List<E> all = U.list();

		Metamodel metamodel = em.getMetamodel();
		Set<EntityType<?>> entityTypes = metamodel.getEntities();

		for (EntityType<?> entityType : entityTypes) {
			List<E> entities = (List<E>) getAll(entityType.getJavaType());
			all.addAll(entities);
		}

		return all;
	}

	public <T> List<T> getAll(Class<T> clazz) {
		CriteriaBuilder cb = em.getCriteriaBuilder();

		CriteriaQuery<T> query = cb.createQuery(clazz);
		CriteriaQuery<T> all = query.select(query.from(clazz));

		return em.createQuery(all).getResultList();
	}

	public void refresh(Object entity) {
		em.refresh(entity);
	}

	public void merge(Object entity) {
		em.merge(entity);
	}

	public <E> void delete(Class<E> clazz, Object id) {
		ensureNotInReadOnlyTransation();
		em.remove(get(clazz, id));
	}

	public void delete(Object entity) {
		ensureNotInReadOnlyTransation();
		em.remove(entity);
	}

	public void transaction(Runnable action, boolean readOnly) {
		final EntityTransaction tx = em.getTransaction();
		U.notNull(tx, "transaction");

		if (readOnly) {
			runTxReadOnly(action, tx);
		} else {
			runTxRW(action, tx);
		}
	}

	private void runTxReadOnly(Runnable action, EntityTransaction tx) {
		boolean txWasActive = tx.isActive();

		if (!txWasActive) {
			tx.begin();
		}

		tx.setRollbackOnly();

		try {
			action.run();
		} catch (Throwable e) {
			tx.rollback();
			throw U.rte("Transaction execution error, rolled back!", e);
		}

		if (!txWasActive) {
			tx.rollback();
		}
	}

	private void runTxRW(Runnable action, EntityTransaction tx) {
		boolean txWasActive = tx.isActive();

		if (!txWasActive) {
			tx.begin();
		}

		try {
			action.run();
		} catch (Throwable e) {
			tx.rollback();
			throw U.rte("Transaction execution error, rolled back!", e);
		}

		if (!txWasActive) {
			tx.commit();
		}
	}

	private void ensureNotInReadOnlyTransation() {
		EntityTransaction tx = em.getTransaction();
		U.must(!tx.isActive() || !tx.getRollbackOnly(), "Cannot perform writes inside read-only transaction!");
	}

	public static JPAUtil with(EntityManager em) {
		return new JPAUtil(em);
	}

}
