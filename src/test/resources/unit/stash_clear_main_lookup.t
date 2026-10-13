print "1..1\n";
%:: = ();
my $got;
{
    no strict 'refs';
    $got = "" . *{q|::|};
}
if ($got eq "*main::main::") {
    print "ok 1 - clearing the main stash preserves lookup of its own glob\n";
} else {
    print "not ok 1 - clearing the main stash preserves lookup of its own glob\n";
    print "# got: $got\n";
}
