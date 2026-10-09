[] client
- send audio file to backend - get text file back
  - OPTIONAL answer some sort of questions based on the audio using gemini api
  - poll the backend for status display text if file is PROCESSED 
    - or use sse/ ws server send event/ websocket
  [] a page for authentication
  [] a page for uploading audio
  [] a way to ask questions based on the audio
[] FileHandler
  - get file from client
  - store the file in a data lake and save the file path (a way to reference files)
  - convert this info into message {jobId, reference, ...}
  - when it receives a msg (text after processing) return it to frontend - change jobId.state = PROCESSED
[] Whisper (stateless)
  - event driven - when the message is recieved convert it into text - save the text
  - return the ctext to filehandler - send message to diff queue - this msg gets picked up by filehandler 