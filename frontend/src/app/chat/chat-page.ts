import { Component } from '@angular/core';

import { ChatPanel } from './chat-panel';

/** The global panel: no `projectId`, so retrieval covers everything the caller can see. */
@Component({
  selector: 'app-chat-page',
  imports: [ChatPanel],
  templateUrl: './chat-page.html',
})
export class ChatPage {}
