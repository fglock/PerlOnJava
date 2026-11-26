use strict;
use warnings FATAL => 'all';

use Test::More;
plan tests => 7;
use_ok('POSIX::strptime');

use POSIX qw(tzset);
$ENV{TZ} = "UTC-1";
tzset();

while(my $rec = <DATA>) {
    chomp $rec;
    my($dstr, $fstr, $rstr, $platform) = split('\|', $rec);

    my @target_ret = map { $_ eq 'undef' ? undef : $_ } split(' ', $rstr);

    my @ret = ( POSIX::strptime($dstr, $fstr) )[0..5];

    # Not all platforms guarantee to 'undef' out elements we didn't touch
    # Only test on the elements it did
    defined $target_ret[$_] or undef $ret[$_] for 0 .. $#target_ret;
    SKIP: {
      skip("Only supported on $platform", 1) if (defined $platform and $^O ne $platform);

      is_deeply(\@ret, \@target_ret, $rec);
    }
}
__DATA__
1899-04-15|%F|undef undef undef 15 3 -1|linux
1707-04-15|%F|undef undef undef 15 3 -193|linux
2005-02-04 10:32:34|%F %T|34 32 10 4 1 105
2005-02-04|%F|undef undef undef 4 1 105
10:32:34|%H:%M:%S|34 32 10 undef undef undef undef undef
blah blah|%H:%M:%S|undef undef undef undef undef undef undef undef
