#!/usr/bin/env perl
use strict;
use warnings;

$SIG{__WARN__} = sub {};
$SIG{__DIE__} = sub {};

# Complete list of ALL Perl operators
my @all_operators = qw(
  + - * / % ** ++ --
  . x
  == != < > <= >= <=>
  eq ne lt gt le ge cmp
  && || ! and or not xor //
  & | ^ ~ << >>
  = += -= *= /= %= **= .= x= &= |= ^= <<= >>= &&= ||= //=
  .. ... ~~ =~ !~
  ? : , => ->
  \ $ @ % & *
  q qq qw qx qr m s tr y
  -r -w -x -o -R -W -X -O -e -z -s -f -d -l -p -S -b -c -t -u -g -k -T -B -M -A -C -X
  if unless while until for foreach elsif else given when default
  continue last next redo goto return die warn eval do
  sub package use require no my our local state
  bless ref defined undef scalar wantarray caller
  __FILE__ __LINE__ __PACKAGE__ __SUB__ __END__ __DATA__ __WARN__ __DIE__
  print printf say read readline readdir seek tell open close
  split join grep map sort reverse
  push pop shift unshift splice
  keys values each exists delete
  length substr index rindex chr ord uc lc ucfirst lcfirst fc
  int abs sqrt exp log sin cos atan2 rand srand time
  chomp chop hex oct sprintf crypt

  socket bind listen accept connect send recv shutdown
  getpeername getsockname getsockopt setsockopt
  getprotobyname getprotobynumber getprotoent setprotoent endprotoent
  getservbyname getservbyport getservent setservent endservent
  gethostbyname gethostbyaddr gethostent sethostent endhostent
  getnetbyname getnetbyaddr getnetent setnetent endnetent

  fork exec system wait waitpid kill alarm sleep
  pipe ioctl fcntl flock select
  chmod chown chroot chdir umask utime
  link unlink symlink readlink rename
  mkdir rmdir opendir closedir rewinddir seekdir telldir
  stat lstat truncate
  getpwuid getpwnam getpwent setpwent endpwent
  getgrgid getgrnam getgrent setgrent endgrent
  getlogin getppid getpgrp setpgrp getpriority setpriority
  times gmtime localtime time
  syscall pack unpack vec
  dbmopen dbmclose tie tied untie
  msgget msgrcv msgsnd msgctl
  semget semop semctl
  shmget shmread shmwrite shmctl
  glob
  exit

  binmode eof fileno getc sysopen sysread syswrite sysseek
  format write formline
  pos quotemeta study reset dump evalbytes
  lock prototype bytes utf8
  import unimport BEGIN CHECK INIT END UNITCHECK DESTROY
  STDIN STDOUT STDERR ARGV DATA CORE
  isa break
  /i /m /s /x /o /g /c /e /ee /a /u /l /d /p /n /r
);

print "Complete list contains ", scalar(@all_operators), " operators\n";

my %not = map { $_ => 1 } @all_operators;
my %ok;

for my $op ( sort @all_operators ) {
    next if $op !~ /^[a-z]\w*$/;
    run_with_timeout(
        sub {
            local $@;
            # print "$op ";
            my $executed = 0;
            for my $param ("(1)") {
                last if $executed++;
                my $cmd = qq{
                        BEGIN { *CORE::GLOBAL::$op = sub { \$ok{$op} = 1; delete \$not{$op}; } }
                        $op$param;
                        # END { *CORE::GLOBAL::$op = sub { die }; }
                        1
                    };
                eval $cmd or do {
                    #print "not: $@\n";
                };
            }
        },
        0.1
    );
}
print "\n";
print "NOT:    ", join( " ", sort keys %not ), "\n";
print "GLOBAL: ", join( " ", sort keys %ok ), "\n";

sub run_with_timeout {
    my ( $code, $timeout ) = @_;

    my $result;
    eval {
        local $SIG{ALRM} = sub { die "timeout\n" };
        alarm($timeout);
        $result = $code->();
        alarm(0);    # Cancel the alarm
    };

    if ($@) {
        alarm(0);    # Ensure alarm is cancelled
        if ( $@ eq "timeout\n" ) {
            warn "Code execution timed out after $timeout seconds\n";
            return undef;
        }
        die $@;      # Re-throw other errors
    }

    return $result;
}

