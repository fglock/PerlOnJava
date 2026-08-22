BEGIN {
    unshift @INC, sub {
        my (undef, $file) = @_;
        return unless $file eq 'Sub/Name.pm';
        my $source = <<'MODULE';
package Sub::Name;
sub subname {
    $::sub_name_calls++;
    return $_[1];
}
1;
MODULE
        open my $fh, '<', \$source or die $!;
        return $fh;
    };
}

my $subname = \&Sub::Name::subname;
my $loaded_before_require = exists $INC{'Sub/Name.pm'};
my $required = eval { require Sub::Name; 1 };

$::sub_name_calls = 0;
my $named = $subname->('Example::named', sub { 7 });

print "1..3\n";
print !$loaded_before_require ? "ok 1" : "not ok 1";
print " - compiling a coderef does not load its package\n";
print $required && $::sub_name_calls == 1 ? "ok 2" : "not ok 2";
print " - explicit require uses the CODE INC hook\n";
print $named->() == 7 ? "ok 3" : "not ok 3";
print " - unresolved coderef is filled by the required definition\n";
