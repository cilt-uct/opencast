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
    const { series } = this.player.videoManifest.metadata;
    const seriesInfo = await fetch(getUrlFromOpencastServer(`/api/series/${ series }/metadata`))
    if (seriesInfo.ok) {
      this._seriesData = await seriesInfo.json();
      this._seriesData = this._seriesData[1].fields;
    }
    this.transcriptionTypes = this._seriesData.find(field => field.id === 'transcription-type')?.value || [];

    const episode = await this.player.getEpisode({episodeId: this.player.videoId});
    const tracks = episode?.mediapackage?.media?.track ?? [];
    const attachments = episode?.mediapackage?.attachments?.attachment ?? [];
    this._captions = tracks.find(track => track.type === 'captions/source' && track.mimetype === 'text/vtt') ||
      attachments.find(att => ['captions/vtt', 'captions/vtt+en-us'].includes(att.type) && att.mimetype === 'text/vtt');
    this._captionsJsonAttachment = attachments.find(att => att.type === 'captions/json'
      && att.mimetype === 'application/json');

    if (this._captionsJsonAttachment) {
      const transcriptResponse = await fetch(this._captionsJsonAttachment.url);
      const transcriptionJson = await transcriptResponse.json();
      this._transcriptionData = transcriptionJson[Object.keys(transcriptionJson)[0]];
      this._transcriptionModel = this._transcriptionData.summary?.model;

      return !!(this._captions || this._captionsJsonAttachment);
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

    // Assign the icons
    Object.entries(iconMapping).forEach(([key, value]) => {
      this[key] = value;
    });
  }

  async getContent() {
    let content;
    // Hide tabs if no AI-generated transcription exists
    if (!this._captionsJsonAttachment) {
      content = createElementWithHtmlText(`
        <div class="transcription-plugin-container">
          <p>I don't have ai-generated stuff!</p>
        </div>
      `);
    } else {
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

      this.initializeCommonElements(content);
      this.addAIModelDisclaimer();
      this.handleTabSwitching(content);
      this.handleLanguageDropdownChange();
      this.setupCaptions();
      this.bindVideoEvents();
      this.handlePopupControls(content);
    }

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

    // Iterate over tabs arrayand dynamically generate the HTML for each tab button
    return tabs
      .filter(({ tab }) => this.transcriptionTypes.includes(tab))
      .map(({ tab, icon, title }) => `
        <button class="tab-button" data-tab="${tab}" title="${title}">
          <span class="tab-icon">${icon}</span>
        </button>
      `).join('');
  }

  // Create the HTML structure for the content of each tab
  createTabContent() {
    const tabContents = [
      { id: 'transcript', class: 'active search-content', content: `
        <div class="input-container">
          <span class="search-icon">&#x1F50D;</span>
          <input type="text" placeholder="${this.player.translate('Find in transcript')}"
            class="search-input form-control"/>
        </div>
        <div class="transcript-text"></div>`
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
        <div class="summary-text">${this.getFormattedContent('summary')}</div>`
      },
      { id: 'key_points', class: 'keypoints-content', content: `
        <div class="key-points-text">${this.getFormattedContent('key_points')}</div>`
      },
      { id: 'practice_questions', class: 'practiceQuestions-content', content: `
        <div class="practice-questions-text">${this.getFormattedContent('practice_questions')}</div>`
      },
      { id: 'multiple_choice', class: 'multipleChoice-content', content: `
        <div class="multiple-choice-text">${this.getFormattedContent('multiple_choice')}</div>`
      },
      { id: 'audio_summary', class: 'audioSummary-content', content: `
        <div class="audio-summary-text">${this.getFormattedContent('audio_summary_script')}</div>`
      },
      { id: 'further_reading', class: 'furtherReading-content', content: `
        <div class="further-reading-text">${this.getFormattedContent('further_reading')}</div>`
      },
      { id: 'study_notes', class: 'studyNotes-content', content: `
        <div class="study-notes-text">${this.getFormattedContent('study_notes')}</div>`
      },
      { id: 'info', class: 'info-content', content: `
        <div class="info-text">${this.getInfoContent()}</div>`
      }
    ];

    return tabContents
      .filter(({ id }) => transcriptionTypes.includes(id))
      .map(({ id, class: className, content }) => `
        <div class="tab-pane ${className}" id="${id}">
          ${content}
        </div>
      `).join('');
  }

  // Generate HTML content for the "Info" tab
  getInfoContent() {
    return `
      <h3>About the Transcription</h3>
      <p>This transcription is generated by WayWithWords using the AI model
        <strong>${this._transcriptionModel}</strong>. While we aim for accuracy, the
        content may be inaccurate or incomplete. If in doubt, watch the full video, or
        consult your lecturer or course material.</p>
      <hr/>
      <h3>Tab Button Icons</h3>
      <ul class="icon-description-list">
        <li><span class="tab-icon">${this.transcriptIcon}</span> Full transcript</li>
        <li><span class="tab-icon">${this.summaryIcon}</span> Main points summary</li>
        <li><span class="tab-icon">${this.keyIcon}</span> Key Points</li>
        <li><span class="tab-icon">${this.questionIcon}</span> Practice questions</li>
        <li><span class="tab-icon">${this.checkIcon}</span> Multiple Choice Questions</li>
        <li><span class="tab-icon">${this.audioIcon}</span> Audio Summary</li>
        <li><span class="tab-icon">${this.bookIcon}</span> Additional resources</li>
        <li><span class="tab-icon">${this.notesIcon}</span> Study Notes</li>
      </ul>
    `;
  }

  // Retrieve and format the content for a specified section of the transcription data.
  getFormattedContent(section) {
    return this._transcriptionData?.[section]?.content?.[0]?.text?.replace(/\n/g, '<br>') || '';
  }

  // Initialize commonly used elements
  initializeCommonElements(content) {
    this._tabButtonsContainer = content.querySelector('.tab-buttons');
    this._tabs = content.querySelectorAll('.tab-button');
    this._tabContents = content.querySelectorAll('.tab-pane');
    this._input = content.querySelector('.search-input');
    this._transcriptContainer = content.querySelector('.transcript-text');
    this._languageDropdown = content.querySelector('.summary-language-dropdown');
    this._summaryText = content.querySelector('.summary-text');
  }

  // Add AI model disclaimer to each tab
  addAIModelDisclaimer() {
    if (this._transcriptionModel) {
      const aiModelText = `This transcript is AI-generated by ${this._transcriptionModel} and
      may be inaccurate or incomplete. If in doubt, watch the full video, or consult your lecturer
      or course material.`;

      this._tabContents.forEach(tabPane => {
        tabPane.insertAdjacentHTML('afterbegin', `<span class="ai-model">${aiModelText}</span><hr/>`);
      });
    }
  }

  // Handle tab switching
  handleTabSwitching(content) {
    this._tabs.forEach(tab => {
      tab.addEventListener('click', () => {
        this._tabs.forEach(t => t.classList.remove('active'));
        this._tabContents.forEach(tc => tc.classList.remove('active'));

        tab.classList.add('active');
        const targetPane = content.querySelector(`#${tab.getAttribute('data-tab')}`);
        if (targetPane) {
          targetPane.classList.add('active');
        }
      });
    });
  }

  // Handle language dropdown changes
  handleLanguageDropdownChange() {
    this._languageDropdown.addEventListener('change', () => {
      const language = this._languageDropdown.value;
      const transcriptionContent = this._transcriptionData[`translation_${language}`]?.content[0]?.text
        || this._transcriptionData.summary?.content[0]?.text || '';
      this._summaryText.innerHTML = transcriptionContent.replace(/\n/g, '<br>');
    });
  }

  // Display captions and setup search input event listeners
  setupCaptions() {
    this.showTranscript();

    this._input.addEventListener('keyup', (evt) => {
      this.handleSearchInput(evt);
    });
  }

  // Handle search input in the transcript tab
  handleSearchInput(evt) {
    if (evt.key === 'Enter' || evt.keyCode === 13) {
      evt.preventDefault();
      if (this.searchTimer) clearTimeout(this.searchTimer);

      this.searchTimer = setTimeout(() => {
        const searchText = this._input.value.trim().toLowerCase();
        this.highlightSearchResults(searchText);
        this.searchTimer = null;
      }, 500);
    }
    evt.stopPropagation();
  }

  // Highlight search results in the transcript tab
  highlightSearchResults(searchText) {
    this._cueElements.forEach(elem => {
      let cueText = elem._cue.captions.join('');
      if (searchText) {
        const regex = new RegExp(`(${searchText})`, 'gi');
        cueText = cueText.replace(regex, '<span class="highlight">$1</span>');
      }
      elem.innerHTML = cueText;

      elem.querySelectorAll('.highlight').forEach(span => {
        span.addEventListener('click', async evt => {
          this.handleHighlightClick(evt, elem);
        });
      });
    });
  }

  // Handle clicks on highlighted search results
  async handleHighlightClick(evt, elem) {
    this._cueElements.forEach(elem => elem.classList.remove('current'));
    const parentElem = evt.target.closest('.result-item');
    if (parentElem) parentElem.classList.add('current');

    await this.player.videoContainer.setCurrentTime(elem._cue.start);
    evt.stopPropagation();
  }

  // Show captions in transcript format
  showTranscript() {
    // Determine if a given language is the current language
    const browserLanguage = navigator.language.substring(0, 2);
    const isCurrentLanguage = (lang) => {
      const currentLanguage = this.player.captionsCanvas.currentCaptions?.language || browserLanguage;
      return lang === currentLanguage;
    };

    const currentCaptions = this.captions.find(lang => isCurrentLanguage(lang.language)) ||
      this.captions[0];
    if (!currentCaptions) return;

    this._cueElements = [];
    let sentenceCounter = 0;
    let paragraphElem = document.createElement('p');

    currentCaptions.cues.forEach((cue, index) => {
      const captionText = cue.captions.join(' ');
      const cueElem = createElementWithHtmlText(`<span class="result-item">${captionText}</span>`);
      cueElem._cue = cue;

      this.setupCueClickEvent(cueElem);
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

  // Setup and handle click events for individual cues
  setupCueClickEvent(cueElem) {
    cueElem.addEventListener('click', async evt => {
      this._cueElements.forEach(elem => elem.classList.remove('current'));
      evt.target.classList.add('current');
      evt.target.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

      const time = evt.target._cue.start;
      await this.player.videoContainer.setCurrentTime(time);

      evt.stopPropagation();
    });
  }

  // Bind necessary video events (play, end, time update)
  bindVideoEvents() {
    this.player.bindEvent(Events.TIMEUPDATE, evt => {
      this.updateCurrentCaptionHighlighting(evt.currentTime, this._cueElements, this._transcriptContainer);
    }, true);

    this.player.bindEvent(Events.ENDED, () => {
      this._transcriptContainer.innerHTML = '';
      this.showTranscript();
    });

    this.player.bindEvent(Events.PLAY, () => {
      this._transcriptContainer.innerHTML = '';
      this._input.value = '';
      this.showTranscript();
      this.updateCurrentCaptionHighlighting(this.player.videoContainer.currentTime,
        this._cueElements, this._transcriptContainer);
    });
  }

  updateCurrentCaptionHighlighting(currentTime, cueElements, transcriptContainer) {
    cueElements.forEach((elem, index) => {
      const isCurrent = elem._cue.start <= currentTime && (elem._cue.end >= currentTime ||
        index === cueElements.length - 1);

      if (isCurrent) {
        elem.classList.add('current');
        elem.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

        const elemPosTop = elem.offsetTop - transcriptContainer.scrollTop;
        if (elemPosTop < 0 || elemPosTop > transcriptContainer.clientHeight) {
          transcriptContainer.scrollTo({ top: elem.offsetTop - 20 });
        }
      } else {
        elem.classList.remove('current');
      }
    });
  }

  // Handle popup controls and layout transitions
  handlePopupControls(content) {
    const pluginCloseButton = document.querySelector(`.popup-content.fixed .title-bar
      .popup-action-buttons .popup-action-button.close-button`);
    const pluginOpenButton = document.querySelector('.button-plugin.fixed-width');
    const transcriptPopupContent = document.querySelector('.popup-container .popup-content.fixed');
    const videoBaseContainer = document.querySelector('.base-video-rect.dynamic');
    const captionsCanvas = document.querySelector('.captions-canvas');

    if (transcriptPopupContent) {
      transcriptPopupContent.id = 'transcriptPopupContent';
      transcriptPopupContent.classList.add('vertical-transcript');
      if (pluginCloseButton && pluginCloseButton.style.display === 'none') {
        pluginCloseButton.style.display = 'block';
      }
    }

    if (videoBaseContainer) {
      const isLandscape = videoBaseContainer.classList.contains('landscape');
      const isPortrait = videoBaseContainer.classList.contains('portrait');

      // Handle layout based on current mode (landscape/portrait)
      if (isLandscape) {
        this.handleLandscapeMode(videoBaseContainer, captionsCanvas);
      } else if (isPortrait) {
        this.handlePortraitMode(videoBaseContainer, transcriptPopupContent, captionsCanvas);
      }
    }

    pluginCloseButton.addEventListener('click', (evt) => {
      evt.stopPropagation();
      this.handleClosePopup(videoBaseContainer, captionsCanvas);
    });

    if (pluginOpenButton) {
      pluginOpenButton.addEventListener('click', () => {
        if (videoBaseContainer) {
          if (videoBaseContainer.classList.contains('landscape')) {
            this.handleLandscapeMode(videoBaseContainer, captionsCanvas);
          } else if (videoBaseContainer.classList.contains('portrait')) {
            this.handlePortraitMode(videoBaseContainer, transcriptPopupContent, captionsCanvas);
          }
        }
      });
    }

    this.setupBurgerMenu(content);
  }

  // Handle transition to landscape mode
  handleLandscapeMode(container, captions) {
    const landscapeContainer = container.querySelector('.landscape-container');

    container.classList.replace('landscape', 'portrait');
    container.classList.add('transcription');

    // Move children to videoBaseContainer
    if (landscapeContainer) {
      while (landscapeContainer.firstChild) {
        container.appendChild(landscapeContainer.firstChild);
      }
      landscapeContainer.remove();
    }

    if (captions && captions.style.display === 'block') {
      captions.classList.add('transcription-enabled');
    }
  }

  // Handle transition to portrait mode
  handlePortraitMode(container, popupContent, captions) {
    container.classList.replace('transcription', 'transcription-sm');

    if (popupContent && popupContent.classList.contains('vertical-transcript')) {
      popupContent.classList.replace('vertical-transcript', 'vertical-transcript-sm');
    }

    if (captions && captions.style.display === 'block') {
      captions.classList.add('transcription-enabled');
    }
  }

  // Handle closing of the popup and resetting the layout
  handleClosePopup(container, captions) {
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

    // Ensure captions reset properly
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

  // Setup and Handle burger menu interactions
  setupBurgerMenu(content) {
    this.menuButton = content.querySelector('.menu-button');
    const burgerMenu = content.querySelector('.burger-menu');

    this.menuButton.addEventListener('click', async () => {
      burgerMenu.classList.toggle('active');
    });

    this._tabs.forEach(button => {
      button.addEventListener('click', () => {
        burgerMenu.classList.remove('active');
      });
    });
  }
}

