java  -Dencryption.key=s3g4fr3d0!! -Denvironment=TST -Dsbt.override.build.repos=true -Dsbt.repository.config=repositories  -Xms2048M -Xmx4096M -XX:MaxPermSize=2048M -Xss1M -XX:+CMSClassUnloadingEnabled -jar bin/sbt-launch.jar "$@";