use re 'eval';

print "1..6\n";
sub Foo99::DESTROY { $Foo99::d++ }

sub check_lifetime {
    my ($test, $expected, $name) = @_;
    my $ok = $Foo99::d == $expected;
    print(($ok ? "ok $test - $name\n" : "not ok $test - $name\n"));
}

{
    run_regex_lifetime_case();
}

sub run_regex_lifetime_case {
    {
        $Foo99::d = 0;
        my $r1;
        {
            my $x = bless [1], 'Foo99';
            $r1 = eval 'qr/(??{$x->[0]})/';
        }
        check_lifetime(1, 0, 'callback capture remains after source qr scope exits');
        my $r2 = eval 'qr/a$r1/';
        check_lifetime(2, 0, 'callback capture remains after interpolating qr construction');
        my $x = 2;
        my $matched = eval '"a1" =~ qr/^$r2$/';
        print(($matched ? "ok 3 - nested eval regex still matches\n"
                        : "not ok 3 - nested eval regex still matches\n"));

        check_lifetime(4, 0, 'callback capture remains after matching');
        "a" =~ /a(?{1})/;
        check_lifetime(5, 0, 'callback capture remains alive while regex is in scope');
    }
    check_lifetime(6, 1, 'callback capture is destroyed after regex scope exits');
}
