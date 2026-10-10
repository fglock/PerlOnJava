BEGIN { $warn = ''; $SIG{__WARN__} = sub { $warn .= join('', @_); } }
my $new_proto = 'Prototype mismatch:';
BEGIN { local $^W = 0; eval qq(sub sub10 () {1} sub sub10 {1}); }
my $prototype = $warn =~ /Prototype mismatch: sub main::sub10 \(\) vs none/;
my $constant_redefinition = $warn =~ /Constant subroutine sub10 redefined/;
print($prototype ? 1 : 0, ',', $constant_redefinition ? 1 : 0, "\n");
