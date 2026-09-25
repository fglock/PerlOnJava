BEGIN { print "1..1\n" }

BEGIN {
    $SIG{__DIE__} = sub {
        $_[0] =~ /\Asyntax error at [^ ]+ line ([0-9]+), at EOF/ or exit 1;
        print $1 == $last_line ? "ok 1\n" : "not ok 1\n";
        exit 0;
    };
}

BEGIN { $last_line = __LINE__ } print 1+
