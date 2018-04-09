# media-file-mover-java

## 기능
- jpg에서 exif 정보를 이용하여 날짜별로 정리
- [metadata-extractor](https://drewnoakes.com/code/exif/)를 사용해서 jpg외에도 meta 데이터를 추출할 수 있으면 정리 가능
- 파일의 처음 1024 * 1024 byte를  MD5로 해싱해서 중복 여부 체크해서 삭제(오래걸리는건 느낌탓(?)이겠지만 md5 해싱 함수가 은근히 느림.)
- 이미지별 저장
  - {이동 폴더}\image\{카메라 모델명}\{년월일}\{file basename}-{시분초}.{확장자}
- 비디오별 저장
  - {이동 폴더}\video\{년월일}\{file basename}-{시분초}.{확장자}
  - 비디오는 카메라 모델명 메타정보를 추출하지 못해서 경로에 추가하지 않음.

## 실행방법
- jvm 8 이상 설치
- 빌드(또는 릴리즈에서 다운로드)

         gradle jar
- 실행 

        java -jar {jar 위치} {사진있는 폴더 최상위 폴더} {이동할 폴더명}
        예) java -jar build\libs\jpgmover.jar c:\photo c:\photo_move

