use strict;
use warnings;
use Test::More;

#line	17 "logical-source.pm"
is(__FILE__, 'logical-source.pm', '__FILE__ honors a tab-separated #line directive');
is(__LINE__, 18, '__LINE__ advances from a tab-separated #line directive');

sub caller_location {
    return (caller)[1, 2];
}

sub caller_location_with_prototype ($$$) {
    return (caller)[1, 2];
}

#line	23
my ($caller_file, $caller_line) = caller_location();
is($caller_file, 'logical-source.pm', 'filename-less tab-separated #line retains the prior filename');
is($caller_line, 23, 'filename-less tab-separated #line updates caller line');
($caller_file, $caller_line) = caller_location_with_prototype(1, 2, 3);
is($caller_file, 'logical-source.pm', 'prototyped caller retains a filename-less directive filename');
is($caller_line, 26, 'prototyped caller retains a filename-less directive line');

#line	41 "caller-logical-source.pm"
($caller_file, $caller_line) = caller_location();
is($caller_file, 'caller-logical-source.pm', 'caller honors a tab-separated #line filename');
is($caller_line, 41, 'caller honors a tab-separated #line number');

#line 100 "later-logical-source.pm"
my $later_marker = 1;

#line                                                                        71
($caller_file, $caller_line) = caller_location();
is($caller_file, 'later-logical-source.pm', 'caller retains the preceding #line filename');
is($caller_line, 71, 'caller honors a space-only #line directive');

#line 73 KASHPRITZA
($caller_file, $caller_line) = caller_location();
is($caller_file, 'KASHPRITZA', 'caller honors a bare #line filename');
is($caller_line, 73, 'caller honors a bare #line filename number');

#line	77
($caller_file, $caller_line) = caller_location();
is($caller_file, 'KASHPRITZA', 'filename-less tab-separated #line retains the previous filename');
is($caller_line, 77, 'filename-less tab-separated #line updates caller line');

{
    package LineDirectiveBeginCaller;
    BEGIN {
        my ($package, $file, $line) = caller;
        ::is($file, 'begin-logical-source.pm', 'caller in BEGIN retains a later #line filename');
        ::is($line, 12345, 'caller in BEGIN retains a later #line number');
#line 12345 "begin-logical-source.pm"
    }
}

#line 81 "unterminated-line-directive
#line 85 invalid filename
($caller_file, $caller_line) = caller_location();
is($caller_file, '"unterminated-line-directive', 'malformed bare filename preserves the preceding directive filename');
is($caller_line, 82, 'malformed bare filename preserves the preceding directive line sequence');

#line 91seven
($caller_file, $caller_line) = caller_location();
is($caller_file, '"unterminated-line-directive', 'glued #line number preserves the preceding directive filename');
is($caller_line, 87, 'glued #line number preserves the preceding directive line sequence');

done_testing;
