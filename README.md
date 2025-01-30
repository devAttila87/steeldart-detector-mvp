# Single Camera Steeldarts Automatic Score Detection MVP

Achieving an overall 95%ish accuracy with the ongoing implementation is the goal. 
Once the MVP is robust enough, almost everyone with a free camera at 1080p will be able to use my MVP as an entirely free Steeldarts auto-scoring solution.

## See yourself
* [![Steeldart Detector MVP](https://img.youtube.com/vi/IEU0wJUhDk0/0.jpg)](https://www.youtube.com/watch?v=IEU0wJUhDk0)

## Detection Results from different horizontal angles but almost on the height of the Bullseye

Camera Angle | Camera Distance | Gaussian | Threshold | Erode | Accuracy | Success Ratio
--- | --- | --- | --- |--- |--- |--- 
45°|50 cm|3|60|2|93.3%|2:30
65°|120 cm|3|60|1|96.5%|1:30
75°|120 cm|5|125|1|100%|0:30
85°|120 cm|5|60|1|93.3%|2:30
65°|120 cm|3|50|1|92.5%|6:80
65°|120 cm|3|55|1|98.4%|3:190

## Requirements: 
* at least 1080p static camera (smartphone camera might also work)
* camera positioned at the dartboard in an angle from 50° up to 80°
* Java 17

## Setup
`video comming soon`

## Download Alpha
https://drive.google.com/drive/folders/1q4eLVK3SdFJBQlM-hmY64F3o52IK8G5j?usp=sharing

## About

This approach is inspired by Jacob D. Delaney`s EE368/CS232 project proposal.
Thanks for your interest.
