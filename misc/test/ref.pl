use strict;

{
my %h = ( a => 1 );
my $v = \%h;
my $x = \%h;
print "$v\n";
print \%h, "\n";
bless $v, "XYZ";
print "$v\n";
print "$x\n";
print \%h, "\n"
}

{
my @a = ( a => 1 );
my $v = \@a;
my $x = \@a;
print "$v\n";
print \@a, "\n";
bless $v, "XYZ";
print "$v\n";
print "$x\n";
print \@a, "\n"
}

{
my $s = 123;
my $v = \$s;
my $x = \$s;
print "$v\n";
print \$s, "\n";
bless $v, "XYZ";
print "$v\n";
print "$x\n";
print \$s, "\n"
}

{
my $s;
my $v = \$s;
my $x = \$s;
print "$v\n";
print \$s, "\n";
bless $v, "XYZ";
print "$v\n";
print "$x\n";
print \$s, "\n"
}

