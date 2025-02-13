/*
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
import{
  createElementWithHtmlText,
  PopUpButtonPlugin,
  Events
} from 'paella-core';

import ListIcon from '../icons/transcriptions.svg';
import KeyIcon from '../icons/key.svg';
import BookIcon from '../icons/book.svg';
import SummaryIcon from '../icons/file-lines.svg';
import CheckIcon from '../icons/list-check.svg';
import AudioIcon from '../icons/microphone-lines.svg';
import NotesIcon from '../icons/note-sticky.svg';
import QuestionIcon from '../icons/question.svg';
import InfoIcon from '../icons/info.svg';

import '../css/TranscriptionPlugin.css';
import { getUrlFromOpencastServer } from '../js/PaellaOpencast';

export default class transcriptionPlugin extends PopUpButtonPlugin {

  getAriaLabel() {
    return 'Show Transcript';
  }

  getDescription() {
    return this.getAriaLabel();
  }

  get captions() {
    return this.player.captionsCanvas?.captions;
  }

  async isEnabled() {
    const seriesId = this.player.videoManifest.metadata.series;
    const seriesInfo = await fetch(getUrlFromOpencastServer(`/api/series/${ seriesId }/metadata`));
    const episode = await this.player.getEpisode({episodeId: this.player.videoId});
    const tracks = episode?.mediapackage?.media?.track ?? [];
    const attachments = episode?.mediapackage?.attachments?.attachment ?? [];

    this.seriesInfo = await seriesInfo.json();
    this._seriesData = this.seriesInfo[1].fields;
    this._transcriptFeatures = this._seriesData.find(field => field.id === 'transcript-features')?.value;
    this._captions = tracks.find(track => track.type === 'captions/source' && track.mimetype === 'text/vtt') ||
      attachments.find(att => ['captions/vtt', 'captions/vtt+en-us'].includes(att.type) && att.mimetype === 'text/vtt');
    this._transcriptFeaturesAttachment = attachments.find(att => att.type === 'captions/json'
      && att.mimetype === 'application/json');

    // Default transcript features to transcript if array is empty
    if (!Array.isArray(this._transcriptFeatures) || this._transcriptFeatures.length === 0) {
      this._transcriptFeatures = ['transcript'];
    }

    if (this._transcriptFeaturesAttachment) {
      const transcriptFeaturesResponse = await fetch(this._transcriptFeaturesAttachment.url);
      const transcriptJson = await transcriptFeaturesResponse.json();
      this._transcriptionData = transcriptJson[Object.keys(transcriptJson)[0]];
      this._transcriptionModel = this._transcriptionData.summary?.model;

      return !!(this._captions || (this._transcriptFeaturesAttachment && this._transcriptFeatures.length > 0));
    }
  }

  async load() {
    const iconMapping = {
      icon: this.player.getCustomPluginIcon(this.name, 'buttonIcon') || ListIcon,
      transcriptIcon: ListIcon,
      keyIcon: KeyIcon,
      questionIcon: QuestionIcon,
      checkIcon: CheckIcon,
      audioIcon: AudioIcon,
      bookIcon: BookIcon,
      notesIcon: NotesIcon,
      summaryIcon: SummaryIcon,
      infoIcon: InfoIcon,
    };

    Object.entries(iconMapping).forEach(([key, value]) => {
      this[key] = value;
    });
  }

  async getContent() {
    let content;
    content = createElementWithHtmlText(`
      <div class="transcription-plugin-container">
        <div class="tabs-container">
          <div class="burger-menu" id="burgerMenu">
            <button class="menu-button" aria-label="Menu">&#8942;</button>
            <div class="dropdown-menu" id="dropdownMenu">
              ${this.createTabButtons()}
            </div>
          </div>
          <div class="right-tabs">
            ${this.createTabButtons()}
          </div>
        </div>
        <div class="tab-content">
          ${this.createTabContent()}
        </div>
      </div>
    `);

    this.videoBaseContainer = document.querySelector('.base-video-rect');
    this.captionsCanvas = document.querySelector('.captions-canvas');
    this._cueElements = [];
    this.bindVideoEvents();

    return content;
  }

  // Create tab buttons
  createTabButtons() {
    const tabs = [
      { tab: 'transcript', icon: this.transcriptIcon, title: 'Transcript' },
      { tab: 'summary', icon: this.summaryIcon, title: 'Summary' },
      { tab: 'key_points', icon: this.keyIcon, title: 'Key points' },
      { tab: 'practice_questions', icon: this.questionIcon, title: 'Q & A' },
      { tab: 'multiple_choice', icon: this.checkIcon, title: 'Multiple choice' },
      { tab: 'audio_summary', icon: this.audioIcon, title: 'Audio summary' },
      { tab: 'further_reading', icon: this.bookIcon, title: 'Further reading' },
      { tab: 'study_notes', icon: this.notesIcon, title: 'Notes' },
      { tab: 'info', icon: this.infoIcon, title: 'Info' }
    ];

    // Filter tabs based on transcription types
    let filteredTabs = tabs.filter(({ tab }) => this._transcriptFeatures.includes(tab));
    if (filteredTabs.length > 1 && !filteredTabs.some(({ tab }) => tab === 'info')) {
      filteredTabs.push(
        tabs.find(({ tab }) => tab === 'info')
      );
    }

    return filteredTabs
      .map(({ tab, icon, title }) => `
        <button class="tab-button ${tab === 'transcript' ? 'active' : ''}" 
        data-tab="${tab}" title="${title}">
          <span class="tab-icon">${icon}</span>
        </button>
      `).join('');
  }

  // Create the HTML structure for the content of each tab
  createTabContent() {
    const aiModelText = `
      <div class="ai-model-text">
        <span class='ai-model'>AI-generated transcript and summaries. Click 
          <span class='tab-icon'>${this.infoIcon}</span> for more info.
        </span>
        <hr/>
      </div>`;

    const tabContents = [
      { id: 'transcript', class: 'active search-content', content: `
        <div class="input-container">
          <button class="search-button" title="Search">&#x1F50D;</button>
          <input type="text" placeholder="${this.player.translate('Find in transcript')}"
            class="search-input form-control"/>
          <button class="clear-button" title="Clear search">&#x2715;</button>
        </div>
        <div class="transcript-text tab-contents"></div>`
      },
      { id: 'summary', class: 'summary-content', content: `
        <div class="summary-header">
          <div class="dropdown-container">
            <select class="summary-language-dropdown">
              <option value="english">English</option>
              <option value="afrikaans">Afrikaans</option>
              <option value="isixhosa">IsiXhosa</option>
            </select>
          </div>
        </div>
        <div class="summary-text tab-contents">${this.getFormattedContent('summary')}</div>`
      },
      { id: 'key_points', class: 'keypoints-content', content: `
        <div class="key-points-text tab-contents">${this.getFormattedContent('key_points')}</div>`
      },
      { id: 'practice_questions', class: 'practiceQuestions-content', content: `
        <div class="practice-questions-text tab-contents">${this.getFormattedContent('practice_questions')}</div>`
      },
      { id: 'multiple_choice', class: 'multipleChoice-content', content: `
        <div class="multiple-choice-text tab-contents">${this.getFormattedContent('multiple_choice')}</div>`
      },
      { id: 'audio_summary', class: 'audioSummary-content', content: `
        <div class="audio-summary-text tab-contents">${this.getFormattedContent('audio_summary_script')}</div>`
      },
      { id: 'further_reading', class: 'furtherReading-content', content: `
        <div class="further-reading-text tab-contents">${this.getFormattedContent('further_reading')}</div>`
      },
      { id: 'study_notes', class: 'studyNotes-content', content: `
        <div class="study-notes-text tab-contents">${this.getFormattedContent('study_notes')}</div>`
      },
      { id: 'info', class: 'info-content', content: `
        <div class="info-text tab-contents">${this.getInfoContent()}</div>`
      }
    ];

    // Filter tabs based on transcription types
    let filteredTabs = tabContents.filter(({ id }) => this._transcriptFeatures.includes(id));

    // If more than one tab is displayed, ensure 'info' tab is included
    if (filteredTabs.length > 1 && !filteredTabs.some(tab => tab.id === 'info')) {
      filteredTabs.push(
        tabContents.find(({ id }) => id === 'info')
      );
    }

    return `${aiModelText}
      ${filteredTabs.map(({ id, class: className, content }) => `
        <div class="tab-pane ${className}" id="${id}">
          ${content}
        </div>
      `).join('')}
    `;
  }

  // Generate HTML content for the "Info" tab
  getInfoContent() {
    const iconDescriptions = [
      { tab: 'transcript', icon: this.transcriptIcon, description: 'Transcript' },
      { tab: 'summary', icon: this.summaryIcon, description: 'Video summary' },
      { tab: 'key_points', icon: this.keyIcon, description: 'Key Points' },
      { tab: 'practice_questions', icon: this.questionIcon, description: 'Practice questions' },
      { tab: 'multiple_choice', icon: this.checkIcon, description: 'Multiple Choice Questions' },
      { tab: 'audio_summary', icon: this.audioIcon, description: 'Audio Summary' },
      { tab: 'further_reading', icon: this.bookIcon, description: 'Additional reading resources' },
      { tab: 'study_notes', icon: this.notesIcon, description: 'Study Notes' }
    ];

    // Filter icons based on the displayed tabs
    const filteredIcons = iconDescriptions.filter(({ tab }) => this._transcriptFeatures.includes(tab));

    return `
      <h3>About the Transcription</h3>
      <p>This transcription is generated by WayWithWords using the AI model
        <strong>${this._transcriptionModel}</strong>. While we aim for accuracy, the
        content may be inaccurate or incomplete. If in doubt, watch the full video, or
        consult your lecturer or course material.</p>
      <hr/>
      <h3>Tab Button Icons</h3>
      <ul class="icon-description-list">
        ${filteredIcons.map(({ icon, description }) => `
          <li><span class="tab-icon">${icon}</span> ${description}</li>
        `).join('')}
      </ul>
    `;
  }

  // Retrieve and format the content for a specified transcript feature.
  getFormattedContent(transcriptFeature) {
    return this._transcriptionData?.[transcriptFeature]?.content?.[0]?.text?.replace(/\n/g, '<br>') || '';
  }

  // Show captions in transcript format
  showTranscript() {
    const browserLanguage = navigator.language.substring(0, 2);
    const isCurrentLanguage = (lang) => {
      const currentLanguage = this.player.captionsCanvas.currentCaptions?.language || browserLanguage;
      return lang === currentLanguage;
    };

    const currentCaptions = this.captions.find(lang => isCurrentLanguage(lang.language)) ||
      this.captions[0];
    if (!currentCaptions) return;

    let sentenceCounter = 0;
    let paragraphElem = document.createElement('p');

    currentCaptions.cues.forEach((cue, index) => {
      const captionText = cue.captions.join(' ');
      const cueElem = createElementWithHtmlText(`<span class="result-item">${captionText}</span>`);
      cueElem._cue = cue;

      paragraphElem.appendChild(cueElem);
      sentenceCounter++;

      if ((/[.!?]$/.test(captionText) && sentenceCounter >= 15) || index === currentCaptions.cues.length - 1) {
        this._transcriptContainer.appendChild(paragraphElem);
        paragraphElem = document.createElement('p');
        sentenceCounter = 0;
      }

      this._cueElements.push(cueElem);
    });
  }

  highlightCurrentCaptions(currentTime, cueElements, transcriptContainer) {
    if (this.isSearching) return;

    cueElements.forEach((elem, index) => {
      const isCurrent = elem._cue.start <= currentTime && (elem._cue.end >= currentTime ||
        index === cueElements.length - 1);

      if (isCurrent) {
        elem.classList.add('current');

        // Auto scroll only if user is not actively scrolling
        if (!this.userScrolling) {
          requestAnimationFrame(() => {
            elem.scrollIntoView({ behavior: 'instant', block: 'nearest' });

            // Adjust scroll to keep the element fully in view
            const elemPosTop = elem.offsetTop - transcriptContainer.scrollTop;
            if (elemPosTop < 0 || elemPosTop > transcriptContainer.clientHeight) {
              transcriptContainer.scrollTo({ top: elem.offsetTop - 80, behavior: 'instant'});
            }
          });
        }
      } else {
        elem.classList.remove('current');
      }
    });
  }

  // Handle search input in the transcript tab
  searchTranscript(evt) {
    if (evt.type === 'click' || evt.key === 'Enter' || evt.keyCode === 13) {
      evt.preventDefault();

      if (this.searchTimer) clearTimeout(this.searchTimer);

      const searchText = this._input.value.trim().toLowerCase();

      // Highlight results, and reset the index if a new search text is provided
      if (this.lastSearchText !== searchText) {
        this.lastSearchText = searchText;
        this.highlightSearchResults(searchText);
        this.highlightIndex = -1;
      }

      // Navigate to the next match
      if (this._highlightedElements.length > 0) {
        this.scrollToNextHighlight();
      }

      evt.stopPropagation();
    }
  }

  // Highlight search results in the transcript tab
  highlightSearchResults(searchText) {
    this._highlightedElements = [];

    this._cueElements.forEach(elem => {
      let cueText = elem._cue.captions.join('');
      if (searchText) {
        const regex = new RegExp(`(${searchText})`, 'gi');
        cueText = cueText.replace(regex, '<span class="highlight">$1</span>');
      }
      elem.innerHTML = cueText;

      // Collect all highlighted elements for navigation
      const matches = elem.querySelectorAll('.highlight');
      matches.forEach(match => this._highlightedElements.push(match));

      // Add click event for individual highlights
      matches.forEach(span => {
        span.addEventListener('click', evt => {
          this.jumpToHighlightedCue(evt, elem);
        });
      });
    });

    // Reset navigation index and scroll to the first match if available
    if (this._highlightedElements.length > 0) {
      this.highlightIndex = 0;
    }
  }

  // Clear highlighted search results
  clearSearchHighlights() {
    this._highlightedElements.forEach(elem => {
      elem.classList.remove('highlight');
      elem.classList.remove('current-highlight');
    });
    this._highlightedElements = [];
  }

  // Handle clicks on highlighted search results
  async jumpToHighlightedCue(evt, elem) {
    this._cueElements.forEach(elem => elem.classList.remove('current'));
    const parentElem = evt.target.closest('.result-item');
    if (parentElem) parentElem.classList.add('current');

    await this.player.videoContainer.setCurrentTime(elem._cue.start);
    evt.stopPropagation();
  }

  // Scroll to the next highlighted search result
  scrollToNextHighlight() {
    if (!this._highlightedElements || this._highlightedElements.length === 0) return;
    this._highlightedElements.forEach(elem => elem.classList.remove('current-highlight'));
    this.highlightIndex = (this.highlightIndex + 1) % this._highlightedElements.length;

    const nextHighlight = this._highlightedElements[this.highlightIndex];
    nextHighlight.classList.add('current-highlight');

    nextHighlight.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  handleUserScroll() {
    this.userScrolling = true;
    clearTimeout(this.autoScrollTimeout);

    // Reset userScrolling flag after a brief delay to detect when scrolling stops.
    this.autoScrollTimeout = setTimeout(() => {
      this.userScrolling = false;
    }, 5000);
  }

  // Handle transition to portrait mode
  handlePortraitMode(container, popupContent, captions) {
    container.classList.add('transcription-sm');

    if (popupContent && popupContent.classList.contains('vertical-transcript')) {
      popupContent.classList.replace('vertical-transcript', 'vertical-transcript-sm');
    }

    if (captions && captions.style.display === 'block') {
      captions.classList.add('transcription-enabled');
    }
  }

  // Handle the closing of the plugin and resetting the layout
  closePlugin(container, captions) {
    const isPortrait = container.classList.contains('portrait');
    const isTranscription = container.classList.contains('transcription');

    if (isPortrait && isTranscription) {
      container.classList.replace('portrait', 'landscape');
      this.recreateLandscapeContainer(container);

      if (captions && captions.style.display === 'block' && captions.classList.contains('transcription-enabled')) {
        captions.classList.remove('transcription-enabled');
      }
    } else if (container.classList.contains('transcription-sm')) {
      container.classList.remove('transcription-sm');
    }

    // Ensure captions layout is reset properly
    if (captions && captions.style.display === 'block' && captions.classList.contains('transcription-enabled')) {
      captions.classList.remove('transcription-enabled');
    }
  }

  // Recreate the landscape container when transitioning back to landscape
  recreateLandscapeContainer(container) {
    const newLandscapeContainer = document.createElement('div');
    newLandscapeContainer.classList.add('landscape-container');

    // Move all elements back into the new landscape-container
    while (container.firstChild) {
      newLandscapeContainer.appendChild(container.firstChild);
    }

    // Append the new landscape-container back to the videoBaseContainer
    container.appendChild(newLandscapeContainer);
  }

  // Bind video events
  bindVideoEvents() {
    this.player.bindEvent(Events.TIMEUPDATE, evt => {
      this.highlightCurrentCaptions(evt.currentTime, this._cueElements, this._transcriptContainer);
    }, true);

    this.player.bindEvent(Events.ENDED, () => {
      this._transcriptContainer.innerHTML = '';
      this.showTranscript();
    });

    this.player.bindEvent(Events.PLAY, () => {
      this._transcriptContainer.innerHTML = '';
      this._input.value = '';

      this.showTranscript();
      this.highlightCurrentCaptions(this.player.videoContainer.currentTime,
        this._cueElements, this._transcriptContainer);
    });

    this.player.bindEvent(Events.HIDE_POPUP, (evt) => {
      const sourcePlugin = evt.plugin || evt.detail?.plugin;
      if(sourcePlugin && sourcePlugin.name === 'org.opencast.paella.transcriptionPlugin') {
        sourcePlugin.videoBaseContainer.classList.remove('portrait');
        sourcePlugin.videoBaseContainer.classList.add('landscape');
        this.closePlugin(sourcePlugin.videoBaseContainer, this.captionsCanvas);
      }
    });

    this.player.bindEvent(Events.SHOW_POPUP, (evt) => {
      const sourcePlugin = evt.plugin || evt.detail?.plugin;

      if(sourcePlugin && sourcePlugin.name === 'org.opencast.paella.transcriptionPlugin') {
        this.userScrolling = false;
        this.autoScrollTimeout = null;
        this.isSearching = false;
        this._transcriptContainer = document.querySelector('.transcript-text');
        sourcePlugin._tabs = document.querySelectorAll('.tab-button');
        sourcePlugin._tabContents = document.querySelectorAll('.tab-pane');
        sourcePlugin._input = document.querySelector('.search-input');
        sourcePlugin._searchButton = document.querySelector('.search-button');
        sourcePlugin._clearButton = document.querySelector('.clear-button');
        sourcePlugin._languageDropdown = document.querySelector('.summary-language-dropdown');
        sourcePlugin._summaryText = document.querySelector('.summary-text');
        sourcePlugin.menuButton = document.querySelector('.menu-button');
        sourcePlugin.burgerMenu = document.querySelector('.burger-menu');
        const aiModelText = document.querySelector('.ai-model-text');

        sourcePlugin.transcriptPopupContainer = Array.from(document.querySelectorAll('.popup-container'))
          .find(popup => popup.style.display === 'block');

        sourcePlugin.transcriptPopupContent = sourcePlugin.transcriptPopupContainer
          .querySelector('.popup-content.fixed');
        sourcePlugin.pluginCloseButton = sourcePlugin.transcriptPopupContent
          .querySelector('.title-bar .popup-action-buttons .popup-action-button.close-button');

        // Change to portrait mode
        sourcePlugin.videoBaseContainer.classList.remove('landscape');
        sourcePlugin.videoBaseContainer.classList.add('dynamic');
        sourcePlugin.videoBaseContainer.classList.add('portrait');

        sourcePlugin.transcriptPopupContainer.id = 'transcriptPopupContainer';
        sourcePlugin.transcriptPopupContainer.classList.add('transcript');
        sourcePlugin.transcriptPopupContent.classList.add('vertical-transcript');

        if (sourcePlugin.pluginCloseButton && sourcePlugin.pluginCloseButton.style.display === 'none') {
          sourcePlugin.pluginCloseButton.style.display = 'block';
        }

        this.showTranscript();

        this.handlePortraitMode(sourcePlugin.videoBaseContainer, sourcePlugin.transcriptPopupContent,
          this.captionsCanvas);

        this._tabs.forEach(button => {
          button.addEventListener('click', () => {
            const activeTab = button.getAttribute('data-tab');
            aiModelText.style.display = activeTab === 'info' ? 'none' : 'block';
          });
        });

        this._tabs.forEach(tab => {
          tab.addEventListener('click', () => {
            this._tabs.forEach(t => t.classList.remove('active'));
            this._tabContents.forEach(tc => tc.classList.remove('active'));

            tab.classList.add('active');
            const targetPane = document.querySelector(`#${tab.getAttribute('data-tab')}`);
            if (targetPane) {
              targetPane.classList.add('active');
            }
          });
        });

        sourcePlugin.menuButton.addEventListener('click', async () => {
          sourcePlugin.burgerMenu.classList.toggle('active');
        });

        sourcePlugin._tabs.forEach(button => {
          button.addEventListener('click', () => {
            sourcePlugin.burgerMenu.classList.remove('active');
          });
        });

        // Attach cue click event handler inside SHOW_POPUP
        sourcePlugin._cueElements.forEach(cueElem => {
          cueElem.addEventListener('click', async evt => {
            this._cueElements.forEach(elem => elem.classList.remove('current'));
            evt.target.classList.add('current');
            evt.target.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

            const time = evt.target._cue.start;
            await this.player.videoContainer.setCurrentTime(time);

            evt.stopPropagation();
          });
        });

        sourcePlugin._input.addEventListener('keyup', (evt) => {
          this.searchTranscript(evt);
        });

        sourcePlugin._searchButton.addEventListener('click', (evt) => {
          this.searchTranscript(evt);
        });

        sourcePlugin._clearButton.addEventListener('click', () => {
          sourcePlugin._input.value = '';
          this.clearSearchHighlights();
        });

        sourcePlugin._languageDropdown.addEventListener('change', () => {
          const language = sourcePlugin._languageDropdown.value;
          const transcriptionContent = this._transcriptionData[`translation_${language}`]?.content[0]?.text
            || this._transcriptionData.summary?.content[0]?.text || '';
          this._summaryText.innerHTML = transcriptionContent.replace(/\n/g, '<br>');
        });

        sourcePlugin.pluginCloseButton.addEventListener('click', (evt) => {
          evt.stopPropagation();
          this.closePlugin(sourcePlugin.videoBaseContainer, this.captionsCanvas);
        });

        sourcePlugin._transcriptContainer.addEventListener('scroll', this.handleUserScroll.bind(this));
      }
    });
  }
}

