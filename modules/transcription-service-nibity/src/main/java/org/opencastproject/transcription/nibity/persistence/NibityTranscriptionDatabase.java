/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.opencastproject.transcription.nibity.persistence;

import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.persistence.EntityManagerFactory;
import javax.persistence.spi.PersistenceProvider;

public class NibityTranscriptionDatabase {

  /**
   * Logging utilities
   */
  private static final Logger logger = LoggerFactory.getLogger(NibityTranscriptionDatabase.class);

  /**
   * Persistence provider set by OSGi
   */
  private PersistenceProvider persistenceProvider;

  /**
   * Factory used to create entity managers for transactions
   */
  protected EntityManagerFactory emf;

  private final long noProviderId = -1;

  /**
   * OSGi callback.
   */
  public void activate(ComponentContext cc) {
    logger.info("Activating persistence manager for transcription service");
  }

  public void setEntityManagerFactory(EntityManagerFactory emf) {
    this.emf = emf;
  }

  /**
   * OSGi callback to set persistence provider.
   */
  public void setPersistenceProvider(PersistenceProvider persistenceProvider) {
    this.persistenceProvider = persistenceProvider;
  }

  /**
   * Add a new job
   */
  public NibityTranscriptionJobControl storeJobControl(String mpId, String trackId, String jobId, String jobStatus,
          long trackDuration, Date dateExpected, String provider) throws NibityTranscriptionDatabaseException {
    long providerId = getProviderId(provider);
    if (providerId != noProviderId) {
      NibityTranscriptionJobControlDto dto = NibityTranscriptionJobControlDto.store(emf.createEntityManager(), mpId, trackId, jobId,
              jobStatus, trackDuration, dateExpected, providerId);
      if (dto != null) {
        return dto.toTranscriptionJobControl();
      }
    }
    return null;
  }

  public TranscriptionProviderControl storeProviderControl(String provider) throws NibityTranscriptionDatabaseException {
    TranscriptionProviderControlDto dto = TranscriptionProviderControlDto.storeProvider(emf.createEntityManager(), provider);
    if (dto != null) {
      logger.info("Transcription provider '{}' stored", provider);
      return dto.toTranscriptionProviderControl();
    }
    logger.warn("Unable to store transcription provider '{}'", provider);
    return null;
  }

  public void deleteJobControl(String jobId) throws NibityTranscriptionDatabaseException {
    NibityTranscriptionJobControlDto.delete(emf.createEntityManager(), jobId);
  }

  public void updateJobControl(String jobId, String jobStatus) throws NibityTranscriptionDatabaseException {
    NibityTranscriptionJobControlDto.updateStatus(emf.createEntityManager(), jobId, jobStatus);
  }

  public NibityTranscriptionJobControl findByJob(String jobId) throws NibityTranscriptionDatabaseException {
    NibityTranscriptionJobControlDto dto = NibityTranscriptionJobControlDto.findByJob(emf.createEntityManager(), jobId);
    if (dto != null) {
      return dto.toTranscriptionJobControl();
    }
    return null;
  }

  public List<NibityTranscriptionJobControl> findByMediaPackage(String mpId) throws NibityTranscriptionDatabaseException {
    List<NibityTranscriptionJobControlDto> list = NibityTranscriptionJobControlDto.findByMediaPackage(emf.createEntityManager(),
            mpId);
    List<NibityTranscriptionJobControl> resultList = new ArrayList<NibityTranscriptionJobControl>();
    for (NibityTranscriptionJobControlDto dto : list) {
      resultList.add(dto.toTranscriptionJobControl());
    }
    return resultList;
  }

  public List<NibityTranscriptionJobControl> findByStatus(String... status) throws NibityTranscriptionDatabaseException {
    List<NibityTranscriptionJobControlDto> list = NibityTranscriptionJobControlDto.findByStatus(emf.createEntityManager(), status);
    List<NibityTranscriptionJobControl> resultList = new ArrayList<NibityTranscriptionJobControl>();
    for (NibityTranscriptionJobControlDto dto : list) {
      resultList.add(dto.toTranscriptionJobControl());
    }
    return resultList;
  }

  public TranscriptionProviderControl findIdByProvider(String provider) throws NibityTranscriptionDatabaseException {
    TranscriptionProviderControlDto dtoProvider = TranscriptionProviderControlDto.findIdByProvider(emf.createEntityManager(), provider);
    if (dtoProvider != null) {
      return dtoProvider.toTranscriptionProviderControl();
    } else {
      // store provider and retrieve id
      TranscriptionProviderControl dto = storeProviderControl(provider);
      if (dto != null) {
        dtoProvider = TranscriptionProviderControlDto.findIdByProvider(emf.createEntityManager(), provider);
        return dtoProvider.toTranscriptionProviderControl();
      }
      return null; // not found
    }
  }

  public TranscriptionProviderControl findProviderById(Long id) throws NibityTranscriptionDatabaseException {
    TranscriptionProviderControlDto dtoProvider = TranscriptionProviderControlDto.findProviderById(emf.createEntityManager(), id);
    if (dtoProvider != null) {
      return dtoProvider.toTranscriptionProviderControl();
    }
    return null;
  }

  private long getProviderId(String provider) throws NibityTranscriptionDatabaseException {
    TranscriptionProviderControl providerInfo = findIdByProvider(provider);
    if (providerInfo != null) {
      return providerInfo.getId();
    }
    return noProviderId;
  }
}
